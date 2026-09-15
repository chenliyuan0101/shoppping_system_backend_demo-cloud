package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.dto.AddressSnapshotVO;
import com.mall.demo.common.dto.CartClaimResultVO;
import com.mall.demo.common.dto.CartItemSnapshotVO;
import com.mall.demo.common.dto.MemberBriefVO;
import com.mall.demo.common.dto.MemberSnapshotVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 用户中心出站客户端（P3-4）：单体对会员/地址/购物车数据的**唯一**访问方式。
 *
 * <p>它对齐的是"单体内原来那 4 个域服务接口"（{@code MemberQueryService} / {@code MemberAdminService} /
 * {@code CartCheckoutService} / {@code AddressQueryService}）——P0 把它们做成"只收发 DTO 的纯接口"，
 * 就是为了这一天：调用点一行不用改，只把实现从"查本地表"换成"调 HTTP"。
 *
 * <h2>HTTP 调用改由声明式接口 {@link UserCenterApi} 承担</h2>
 * 路径**逐字对齐** {@code mall-user-center} 的 {@code /internal/v1/user/**}
 * （原单体的 {@code InternalUserController} 平移到那边），因此切换当天只改调用方，不改契约。
 * 本类保留"域语义 + 数据形状"：连接装配、错误语义（传输异常/空响应 → 500；
 * 业务码非 0 → 原样透传，{@code 404「会员不存在」}之类不能变成 500）、
 * 空集合/空关键字的 body 拼装、{@code LocalDateTime} → 字符串的时间口径。
 *
 * <p>购物车结算两阶段（P3 的购物车闸门改造，见方案 §5 P3）：
 * {@link #claimCartItems} 以 {@code orderNo} 为幂等键领取购物车明细；订单事务回滚时由调用方
 * 显式调用 {@link #restoreCartItems} 归还——**不再依赖"远程删除会被本地事务回滚"**（那个前提已失效）。
 *
 * <p>内部密钥 {@code X-Internal-Token} 不再手工写：{@code lb://} 走 {@code @LoadBalanced} builder 上的
 * {@code OutboundHeadersInterceptor}，{@code http://} 直连由
 * {@link OutboundRestClientFactory} 显式挂同一个拦截器。
 *
 * <p>{@link #get(String, Supplier)} / {@link #post(String, Supplier)} / {@link #exchange(String, Supplier)}
 * 的 action 标签（{@code "GET /member/{id}"}、{@code "POST /member/page"}、{@code "updateMemberStatus"}）
 * 与日志文案与迁移前**逐字相同**。
 */
@Slf4j
@Component
public class UserCenterClient {

    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final UserCenterApi api;

    public UserCenterClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                            OutboundRestClientFactory restClients,
                            @Value("${mall.user-center.base-url:lb://mall-user-center}") String baseUrl,
                            @Value("${mall.user-center.connect-timeout-ms:300}") long connectTimeoutMs,
                            @Value("${mall.user-center.read-timeout-ms:2000}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(UserCenterApi.class);
        log.info("用户中心客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // ==================== 会员 ====================

    public MemberBriefVO member(long memberId) {
        return get("/member/{id}", () -> api.member(memberId));
    }

    /**
     * 会员**完整**快照（后台会员详情用）：{@code {id,username,nickname,phone,avatar,status,createTime}}。
     *
     * <p>为什么不复用 {@link #member(long)}：那个是"简要"（{@code MemberBriefVO}，给订单列表补名字用），
     * 只有 id/username/nickname/phone。后台详情页要 avatar/status/createTime，用简要快照会静默丢字段。
     *
     * <p>⚠️ 这段注释是踩坑记录：最初版本没有这个端点，远程实现只好用
     * {@code /member/page?keyword=<id>} 反查——只有"用户名/手机号/昵称里含这串数字"才命中，
     * 其他情况直接 404。**按 id 取详情必须有按 id 的契约**，用检索接口反查是错误的等价替换。
     *
     * @return 会员不存在（含逻辑删除）→ {@code null}（不是异常：调用方据此返回 404 语义）
     */
    public MemberSnapshotVO memberSnapshot(long memberId) {
        return get("/member/{id}/snapshot", () -> api.memberSnapshot(memberId));
    }

    public long memberCount() {
        Long count = get("/member/count", () -> api.memberCount());
        return count == null ? 0L : count;
    }

    public List<MemberBriefVO> members(Collection<Long> memberIds) {
        return post("/member/batch", () -> api.members(Map.of("memberIds", emptyIfNull(memberIds))));
    }

    public List<Long> searchMemberIds(String keyword) {
        return post("/member/search-ids", () -> api.searchMemberIds(Map.of("keyword", keyword == null ? "" : keyword)));
    }

    /**
     * 会员分页（后台列表）。
     *
     * <p>主查必须留在属主域：分页的 total 与 list 要在同一个库、同一时刻算出来，
     * 否则"总数 200 但翻到第 3 页是空的"这类问题会变成常态。
     */
    public PageResult<MemberSnapshotVO> memberPage(String keyword, Integer status,
                                            LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                            long pageNum, long pageSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("keyword", keyword);
        body.put("createTimeStart", createTimeStart == null ? null : createTimeStart.toString());
        body.put("createTimeEnd", createTimeEnd == null ? null : createTimeEnd.toString());
        body.put("status", status);
        body.put("pageNum", pageNum);
        body.put("pageSize", pageSize);
        return post("/member/page", () -> api.memberPage(body));
    }

    /** 管理员改会员状态（属主域内保证"禁用即失效令牌"这条不变量） */
    public void updateMemberStatus(long memberId, int status) {
        exchange("updateMemberStatus",
                () -> api.updateMemberStatus(memberId, Map.<String, Object>of("status", status)));
    }

    // ==================== 地址 ====================

    /** 默认地址；不存在返回 null（下单时按"请选择收货地址"处理） */
    public AddressSnapshotVO defaultAddress(long memberId) {
        return get("/address/default?memberId={id}", () -> api.defaultAddress(memberId));
    }

    /** 指定地址；不存在或不属于该会员 → null（不区分两者，避免探测他人地址） */
    public AddressSnapshotVO address(long memberId, long addressId) {
        return get("/address/{aid}?memberId={mid}", () -> api.address(addressId, memberId));
    }

    // ==================== 购物车（结算闸门两阶段） ====================

    /** 读要结算的购物车条目（不含价格——价格必须由商品域现算） */
    public List<CartItemSnapshotVO> cartItems(long memberId, Collection<Long> itemIds) {
        return post("/cart/items", () -> api.cartItems(
                Map.of("memberId", memberId, "itemIds", emptyIfNull(itemIds))));
    }

    /**
     * 结算闸门（幂等领取）：清空这些明细并返回是否领到。
     *
     * <p>幂等键是 {@code orderNo}：**同一订单重试 → 视为已领取**（返回 claimed=true 与当时的明细快照），
     * 而不是"0 行 → 已被别人结算"。这样"用户双击提交"不会因为第二次拿不到闸门而误报 409，
     * 也不会双扣库存（库存由商品域按 orderNo 幂等）。
     * 不同订单抢同一批明细时，仍然只有一方拿到。
     */
    public CartClaimResultVO claimCartItems(long memberId, String orderNo, Collection<Long> itemIds) {
        return post("/cart/claim", () -> api.claimCartItems(
                Map.of("memberId", memberId, "orderNo", orderNo, "itemIds", emptyIfNull(itemIds))));
    }

    /**
     * 补偿：把某个订单已领取的明细还回购物车（订单事务回滚时调用）。
     *
     * <p>幂等：重复调用不会重复归还；没有领取记录时是空操作。**失败不抛异常**——
     * 补偿发生在事务回滚阶段，此时再抛异常只会掩盖原始失败原因；失败要留日志并由对账兜底。
     */
    public boolean restoreCartItems(long memberId, String orderNo) {
        try {
            Boolean restored = post("/cart/restore",
                    () -> api.restoreCartItems(Map.of("memberId", memberId, "orderNo", orderNo)));
            return Boolean.TRUE.equals(restored);
        } catch (Exception e) {
            log.error("购物车明细补偿失败(需对账兜底): memberId={} orderNo={}", memberId, orderNo, e);
            return false;
        }
    }


    // ==================== 内部 ====================

    /**
     * GET 的统一入口。
     *
     * <p>迁移前这里要求调用方传 {@code ParameterizedTypeReference}（泛型擦除的坑，见
     * {@link UserCenterApi} 的类注释）；现在类型由接口方法签名给出，
     * 本方法只负责"action 标签 + 错误语义"，{@code uriTemplate} 仅用于日志标签（与迁移前逐字一致）。
     */
    private <T> T get(String uriTemplate, Supplier<ApiResponse<T>> invocation) {
        return exchange("GET " + uriTemplate, invocation);
    }

    private <T> T post(String path, Supplier<ApiResponse<T>> invocation) {
        return exchange("POST " + path, invocation);
    }

    /**
     * 错误语义的唯一落点：传输异常/空响应 → 500「系统繁忙，请稍后重试」；业务码非 0 → 原样透传。
     *
     * <p>为什么声明式接口之后仍然要这层 try/catch：接口解决的是"HTTP ↔ 类型"的样板，
     * **不解决错误语义**——连接被拒/超时抛 {@code ResourceAccessException}，4xx/5xx 抛
     * {@code HttpClientErrorException}，两者都要在这里变成"域的失败"。
     */
    private <T> T exchange(String action, Supplier<ApiResponse<T>> invocation) {
        ApiResponse<T> response;
        try {
            response = invocation.get();
        } catch (Exception e) {
            log.error("调用用户中心失败: action={}", action, e);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response == null) {
            log.error("调用用户中心返回空响应: action={}", action);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            // 业务错误原样透传（404「会员不存在」之类不能变成 500）
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }

    private static <T> List<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
