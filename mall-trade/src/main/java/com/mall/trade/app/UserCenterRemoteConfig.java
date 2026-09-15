package com.mall.trade.app;

import com.mall.trade.common.contract.MemberAdminService;
import com.mall.trade.common.contract.MemberQueryService;
import com.mall.trade.common.BusinessException;
import com.mall.trade.common.client.UserCenterClient;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.dto.AddressSnapshotVO;
import com.mall.trade.common.dto.CartClaimResultVO;
import com.mall.trade.common.dto.CartItemSnapshotVO;
import com.mall.trade.common.dto.MemberBriefVO;
import com.mall.trade.common.dto.MemberSnapshotVO;
import com.mall.trade.common.contract.AddressQueryService;
import com.mall.trade.common.contract.CartCheckoutService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 用户中心接线（P3-4）：{@code mall.user-center.remote=true} 时，把 4 个会员域契约
 * 实现成"调用 user-center 内部接口"。
 *
 * <p><b>P3-4 收口后这已经是单体唯一的实现</b>：会员/地址/购物车的本地实现与对应的
 * {@code ums_member}/{@code ums_address}/{@code ums_cart_item} 等表一起迁走了，开关保留只为
 * "生产恒 true / 测试由测试替身顶上"这一件事，不要再把它切回 false
 * （切回去的结果是"没有 MemberQueryService 这个 bean"，启动即失败）。
 * 测试侧实现见 {@code src/test/java/.../UserCenterTestDoubleConfig}。
 * 注意：{@code ums_notification} 的写入路径 P3-5 已搬到 user-center（本单体只保留订单日统计），
 * 因此本类不管站内消息。
 *
 * <p>为什么收在一个 {@code @Configuration} 里而不是 4 个 {@code XxxRemoteImpl} 文件：
 * 集中在一处的好处是"一眼能看全哪些契约映射到哪些端点、空值语义怎么处理"。
 *
 * <p><b>为什么这些实现是 class 而不是 record</b>（实测踩坑）：record 是 <b>final</b> 类，
 * 而 Spring 需要为它生成 CGLIB 代理（事务/切面匹配到该 bean 时）——启动直接失败：
 * {@code Could not generate CGLIB subclass ... using a final class or a non-visible class}。
 * 因此这里用 {@code public static class} + 显式构造器；**不要**为了简洁改回 record。
 *
 * <p>契约映射纪律（与 {@link UserCenterClient} 的注释一致）：
 * 业务错误码/文案原样透传（404「会员不存在」不能变成 500），传输失败 → 500「系统繁忙，请稍后重试」。
 *
 * <p><b>为什么本类必须放在组装根 {@code app}</b>：它是整个工程里<b>唯一</b>有权知道
 * "哪些契约由哪个服务实现"的地方——接线（依赖具体实现）与契约（{@code common.contract}）分开，
 * 契约本身才不会被绑上实现细节。
 */
@Configuration
@ConditionalOnProperty(name = "mall.user-center.remote", havingValue = "true")
public class UserCenterRemoteConfig {

    @Bean
    public MemberQueryService memberQueryService(UserCenterClient client) {
        return new RemoteMemberQuery(client);
    }

    @Bean
    public MemberAdminService memberAdminService(UserCenterClient client) {
        return new RemoteMemberAdmin(client);
    }

    @Bean
    public AddressQueryService addressQueryService(UserCenterClient client) {
        return new RemoteAddressQuery(client);
    }

    @Bean
    public CartCheckoutService cartCheckoutService(UserCenterClient client) {
        return new RemoteCartCheckout(client);
    }

    // ==================== 实现 ====================

    /** 会员只读契约 → {@code /internal/v1/user/member/**} */
    public static class RemoteMemberQuery implements MemberQueryService {

        private final UserCenterClient client;

        public RemoteMemberQuery(UserCenterClient client) {
            this.client = client;
        }

        @Override
        public MemberBriefVO brief(Long memberId) {
            return memberId == null ? null : client.member(memberId);
        }

        @Override
        public List<MemberBriefVO> briefs(Collection<Long> memberIds) {
            return memberIds == null || memberIds.isEmpty() ? List.of() : client.members(memberIds);
        }

        @Override
        public long count() {
            return client.memberCount();
        }

        @Override
        public MemberSnapshotVO snapshot(Long memberId) {
            // 直调 /member/{id}/snapshot：按 id 取详情必须有按 id 的契约。
            // （曾经的实现用 /member/page?keyword=id 反查，只有"名字里含这串数字"才命中——
            //   这类"看起来能用"的等价替换是拆分时最危险的错误，已在注释里留档。）
            return memberId == null ? null : client.memberSnapshot(memberId);
        }

        @Override
        public PageResult<MemberSnapshotVO> page(String keyword, Integer status,
                                                 LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                                 long pageNum, long pageSize) {
            return client.memberPage(keyword, status, createTimeStart, createTimeEnd, pageNum, pageSize);
        }

        @Override
        public List<Long> searchIds(String keyword) {
            return client.searchMemberIds(keyword);
        }
    }

    /** 会员写契约 → {@code /member/{id}/status}（"禁用即失效令牌"的不变量在属主域内完成） */
    public static class RemoteMemberAdmin implements MemberAdminService {

        private final UserCenterClient client;

        public RemoteMemberAdmin(UserCenterClient client) {
            this.client = client;
        }

        @Override
        public void updateStatus(Long memberId, Integer status) {
            if (memberId == null || status == null) {
                throw new BusinessException(400, "状态值仅支持 0禁用 1正常");
            }
            client.updateMemberStatus(memberId, status);
        }
    }

    /** 地址契约 → {@code /address/**}（归属校验与默认地址排序规则留在属主域） */
    public static class RemoteAddressQuery implements AddressQueryService {

        private final UserCenterClient client;

        public RemoteAddressQuery(UserCenterClient client) {
            this.client = client;
        }

        @Override
        public AddressSnapshotVO defaultAddress(Long memberId) {
            return memberId == null ? null : client.defaultAddress(memberId);
        }

        @Override
        public AddressSnapshotVO address(Long memberId, Long addressId) {
            return memberId == null || addressId == null ? null : client.address(memberId, addressId);
        }
    }

    /** 购物车结算契约 → {@code /cart/**}（两阶段闸门：claim + restore） */
    public static class RemoteCartCheckout implements CartCheckoutService {

        private final UserCenterClient client;

        public RemoteCartCheckout(UserCenterClient client) {
            this.client = client;
        }

        @Override
        public List<CartItemSnapshotVO> items(Long memberId, Collection<Long> itemIds) {
            return memberId == null || itemIds == null || itemIds.isEmpty()
                    ? List.of() : client.cartItems(memberId, itemIds);
        }

        @Override
        public boolean consumeItems(Long memberId, Collection<Long> itemIds) {
            // 过渡形态：远程模式下**故意不支持**。
            // 它的语义（靠调用方本地事务回滚来撤销删除）在跨进程后不成立，
            // 下单链路已经改用 claim + restore（见 OrderServiceImpl）。
            throw new UnsupportedOperationException(
                    "consumeItems 在远程模式下不可用：请改用两阶段闸门 claim/restore");
        }

        @Override
        public CartClaimResultVO claim(Long memberId, String orderNo, Collection<Long> itemIds) {
            if (memberId == null || orderNo == null || itemIds == null || itemIds.isEmpty()) {
                return CartClaimResultVO.notClaimed();
            }
            CartClaimResultVO result = client.claimCartItems(memberId, orderNo, itemIds);
            return result == null ? CartClaimResultVO.notClaimed() : result;
        }

        @Override
        public boolean restore(Long memberId, String orderNo) {
            return memberId != null && orderNo != null && client.restoreCartItems(memberId, orderNo);
        }
    }
}
