package com.mall.trade.common.client;

import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.dto.AddressSnapshotVO;
import com.mall.trade.common.dto.CartClaimResultVO;
import com.mall.trade.common.dto.CartItemSnapshotVO;
import com.mall.trade.common.dto.MemberBriefVO;
import com.mall.trade.common.dto.MemberSnapshotVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;
import com.mall.common.support.MemberId;

/**
 * <b>用户中心内部契约的声明式接口</b>（Spring HTTP Interface，替换 {@link UserCenterClient} 里原先手写的
 * {@code RestClient} 链）。
 *
 * <p>路径**逐字对齐** {@code mall-user-center} 的 {@code /internal/v1/user/**}
 * （原单体的 {@code InternalUserController} 平移到那边），因此本次迁移只换"怎么发"，不换契约：
 * <ul>
 *   <li>会员：{@code GET /member/{id}}、{@code GET /member/{id}/snapshot}、{@code GET /member/count}、
 *       {@code POST /member/batch}、{@code POST /member/search-ids}、{@code POST /member/page}、
 *       {@code POST /member/{id}/status}</li>
 *   <li>地址：{@code GET /address/default?memberId=…}、{@code GET /address/{aid}?memberId=…}</li>
 *   <li>购物车（结算闸门两阶段）：{@code POST /cart/items}、{@code POST /cart/claim}、{@code POST /cart/restore}</li>
 * </ul>
 *
 * <h2>这层解决的老坑</h2>
 * 原先 {@code get(uriTemplate, ParameterizedTypeReference, vars)} 要求**每个调用点**显式传类型：
 * 泛型方法里的 {@code T} 会被擦除，Jackson 只能把 {@code data} 反序列化成 {@code LinkedHashMap}，
 * 调用方随后 {@code ClassCastException}——编译期完全看不出来（P3-4 切远程模式时实测踩到，
 * 表现为"后台会员详情 500"）。声明式接口的返回类型是**方法签名的一部分**，框架从
 * {@code MethodParameter} 解析泛型 ⇒ 这个坑结构性消失。
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包、空响应判定与
 * 日志留在 {@link UserCenterClient}。
 *
 * <p>⚠️ 出站头 {@code X-Internal-Token} / {@code X-Trace-Id} 由 {@code OutboundHeadersInterceptor}
 * 统一注入，这里与客户端类都**不再**手工写 {@code .header(...)}。
 */
@HttpExchange(url = "/internal/v1/user", contentType = "application/json")
public interface UserCenterApi {

    // ==================== 会员 ====================

    /** 会员简要信息（订单列表补名字用） */
    @GetExchange("/member/{id}")
    ApiResponse<MemberBriefVO> member(@PathVariable("id") long memberId);

    /** 会员**完整**快照（后台会员详情用；不存在 → data 为 null，由调用方返回 404 语义） */
    @GetExchange("/member/{id}/snapshot")
    ApiResponse<MemberSnapshotVO> memberSnapshot(@PathVariable("id") long memberId);

    @GetExchange("/member/count")
    ApiResponse<Long> memberCount();

    /** 批量会员简要信息；body {@code {memberIds:[…]}}（null → 空列表发出去，见客户端类） */
    @PostExchange("/member/batch")
    ApiResponse<List<MemberBriefVO>> members(@RequestBody Map<String, Object> body);

    /** 按关键字检索会员 id；body {@code {keyword}}（null → 空串） */
    @PostExchange("/member/search-ids")
    ApiResponse<List<Long>> searchMemberIds(@RequestBody Map<String, Object> body);

    /**
     * 会员分页（后台列表）。
     *
     * <p>body 是 {@code {keyword,createTimeStart,createTimeEnd,status,pageNum,pageSize}}，
     * 其中两个时间字段是**字符串**（{@code LocalDateTime.toString()}）——下游按既有的字符串契约解析，
     * 拼 body 的事留在客户端类里做，形状与迁移前一字不差。
     */
    @PostExchange("/member/page")
    ApiResponse<PageResult<MemberSnapshotVO>> memberPage(@RequestBody Map<String, Object> body);

    /** 管理员改会员状态；body {@code {status}} */
    @PostExchange("/member/{id}/status")
    ApiResponse<Void> updateMemberStatus(@PathVariable("id") long memberId, @RequestBody Map<String, Object> body);

    // ==================== 地址 ====================

    /** 默认地址（查询参数，不是路径） */
    @GetExchange("/address/default")
    ApiResponse<AddressSnapshotVO> defaultAddress(@RequestParam("memberId") long memberId);

    /** 指定地址（路径是 addressId，查询参数是 memberId —— 与迁移前的 {@code /address/{aid}?memberId={mid}} 同形） */
    @GetExchange("/address/{aid}")
    ApiResponse<AddressSnapshotVO> address(@PathVariable("aid") long addressId,
                                          @RequestParam("memberId") long memberId);

    // ==================== 购物车（结算闸门两阶段） ====================

    /** 读要结算的购物车条目（不含价格——价格必须由商品域现算）；body {@code {memberId,itemIds:[…]}} */
    @PostExchange("/cart/items")
    ApiResponse<List<CartItemSnapshotVO>> cartItems(@RequestBody Map<String, Object> body);

    /** 结算闸门（幂等领取，幂等键 {@code orderNo}）；body {@code {memberId,orderNo,itemIds:[…]}} */
    @PostExchange("/cart/claim")
    ApiResponse<CartClaimResultVO> claimCartItems(@RequestBody Map<String, Object> body);

    /** 补偿：归还已领取的明细（订单事务回滚时）；body {@code {memberId,orderNo}} */
    @PostExchange("/cart/restore")
    ApiResponse<Boolean> restoreCartItems(@RequestBody Map<String, Object> body);
}
