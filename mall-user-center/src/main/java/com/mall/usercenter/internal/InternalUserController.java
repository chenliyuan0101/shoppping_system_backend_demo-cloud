package com.mall.usercenter.internal;

import com.mall.usercenter.service.AddressQueryService;
import com.mall.usercenter.service.CartCheckoutService;
import com.mall.usercenter.service.MemberAdminService;
import com.mall.usercenter.service.MemberQueryService;
import com.mall.usercenter.support.ApiResponse;
import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.support.dto.AddressSnapshotVO;
import com.mall.usercenter.support.dto.CartClaimResultVO;
import com.mall.usercenter.support.dto.CartItemSnapshotVO;
import com.mall.usercenter.support.dto.MemberBriefVO;
import com.mall.usercenter.support.dto.MemberSnapshotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会员/地址/购物车的<b>内部接口</b>（服务间调用，{@code /internal/**} 由
 * {@code InternalApiAuthInterceptor} 守：未配置凭据一律 403）。
 *
 * <p>这批端点是单体 {@code internal.InternalUserController} 的平移（路径、请求体、响应体逐字一致），
 * 外加 P3 补齐的两个缺口：
 * <ul>
 *   <li>{@code POST /member/page}：后台会员列表的<b>分页主查</b>（原单体只有同进程调用，
 *       没有 HTTP 契约；分页必须留在属主域，否则 total 与 list 不再是同一时刻的快照）；</li>
 *   <li>{@code POST /member/{id}/status}：后台"禁用/启用会员"。规则留在属主域内执行——
 *       <b>禁用 ⇒ 令牌版本 +1</b> 由 {@link MemberAdminService} 保证，
 *       调用方不可能"忘了 bump"（这是拆服务后最容易出的漏网：状态改了但旧 token 还能用）。</li>
 * </ul>
 *
 * <p>另外把旧的单阶段结算闸门 {@code /cart/consume} 换成了两阶段
 * {@code /cart/claim} + {@code /cart/restore}：删除动作不再依赖"调用方事务回滚"这个跨进程不成立的前提
 * （详见 {@link CartCheckoutService}）。
 *
 * <p>会员的 {@code GET /member/count} 与 {@code GET /member/{id}/status}（成员状态快照）
 * 仍在 {@code InternalMemberController}——它们属 P3-1 已验收的契约，路径与形状不动。
 */
@RestController
@RequestMapping("/internal/v1/user")
@RequiredArgsConstructor
public class InternalUserController {

    /** 批量取会员概要 */
    public record MemberIdsRequest(List<Long> memberIds) {
    }

    /** 按关键字检索会员 id */
    public record KeywordRequest(String keyword) {
    }

    /**
     * 会员分页（后台列表主查）：条件全部作用在 {@code ums_member} 上。
     *
     * <p>注册时间段用 ISO-8601 字符串传（调用方 {@code UserCenterClient} 发的是
     * {@code LocalDateTime#toString()}，如 {@code 2026-09-01T00:00}）——{"起（含）/止（不含）"}
     * 的半开区间口径与 {@code MemberQueryService.page} 一致。
     */
    public record MemberPageRequest(String keyword, Integer status,
                                    LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                    Long pageNum, Long pageSize) {
    }

    /** 改会员状态：0 禁用 / 1 正常 */
    public record MemberStatusRequest(Integer status) {
    }

    /** 购物车条目读写（读要结算的条目 / 领取闸门） */
    public record CartItemsRequest(Long memberId, List<Long> itemIds) {
    }

    /** 结算闸门领取：以 {@code orderNo} 为幂等键 */
    public record CartClaimRequest(Long memberId, String orderNo, List<Long> itemIds) {
    }

    /** 结算闸门归还（订单回滚补偿） */
    public record CartRestoreRequest(Long memberId, String orderNo) {
    }

    private final MemberQueryService memberQueryService;
    private final MemberAdminService memberAdminService;
    private final AddressQueryService addressQueryService;
    private final CartCheckoutService cartCheckoutService;

    @GetMapping("/member/{id}")
    public ApiResponse<MemberBriefVO> member(@PathVariable Long id) {
        return ApiResponse.ok(memberQueryService.brief(id));
    }

    /**
     * 会员<b>档案快照</b>（后台会员详情用：比概要多头像/状态/注册时间）。
     *
     * <p>为什么要有它：{@code MemberQueryService.snapshot(id)} 本来是同进程契约，拆服务后
     * 一度只能靠 {@code /member/page?keyword=id} 反查——那既不准（id 未必出现在用户名/手机号/昵称里）
     * 又白搭一次分页查询。补一个**按 id 直取**的正式端点，语义与领域契约一一对应。
     *
     * <p>不存在（含逻辑删除）→ {@code data: null}，**不抛 404**：调用方用它表达"没有这个会员"，
     * 而不是"调用失败"——与 {@link #member(Long)} 的既有口径一致，抛 404 会让两种情况无法区分。
     */
    @GetMapping("/member/{id}/snapshot")
    public ApiResponse<MemberSnapshotVO> memberSnapshot(@PathVariable Long id) {
        return ApiResponse.ok(memberQueryService.snapshot(id));
    }

    @PostMapping("/member/batch")
    public ApiResponse<List<MemberBriefVO>> members(@RequestBody MemberIdsRequest request) {
        return ApiResponse.ok(memberQueryService.briefs(nullSafe(request.memberIds())));
    }

    @PostMapping("/member/search-ids")
    public ApiResponse<List<Long>> searchMemberIds(@RequestBody KeywordRequest request) {
        return ApiResponse.ok(memberQueryService.searchIds(request.keyword()));
    }

    /**
     * 会员分页（后台会员列表的分页主查）。
     *
     * <p>页码/每页条数的收敛在会员域内完成（{@code PageKit}）：调用方传非法值不会拼出
     * {@code LIMIT -N} 这种导致 500 的 SQL。未传时按前台同一口径取第 1 页、每页 10 条。
     */
    @PostMapping("/member/page")
    public ApiResponse<PageResult<MemberSnapshotVO>> memberPage(@RequestBody MemberPageRequest request) {
        long pageNum = request.pageNum() == null ? 1L : request.pageNum();
        long pageSize = request.pageSize() == null ? 10L : request.pageSize();
        return ApiResponse.ok(memberQueryService.page(request.keyword(), request.status(),
                request.createTimeStart(), request.createTimeEnd(), pageNum, pageSize));
    }

    /**
     * 改会员状态（后台）。**禁用即失效**：令牌版本 +1 与状态缓存失效都在属主域内完成，
     * 调用方拿到 code 0 时，"旧 token 立刻不可用"这件事已经发生。
     */
    @PostMapping("/member/{id}/status")
    public ApiResponse<Void> updateMemberStatus(@PathVariable Long id, @RequestBody MemberStatusRequest request) {
        memberAdminService.updateStatus(id, request.status());
        return ApiResponse.ok();
    }

    /** 默认地址（下单用） */
    @GetMapping("/address/default")
    public ApiResponse<AddressSnapshotVO> defaultAddress(@RequestParam Long memberId) {
        return ApiResponse.ok(addressQueryService.defaultAddress(memberId));
    }

    /** 指定地址；不存在或不属于该会员时 data 为 null */
    @GetMapping("/address/{addressId}")
    public ApiResponse<AddressSnapshotVO> address(@PathVariable Long addressId, @RequestParam Long memberId) {
        return ApiResponse.ok(addressQueryService.address(memberId, addressId));
    }

    /** 读要结算的购物车条目 */
    @PostMapping("/cart/items")
    public ApiResponse<List<CartItemSnapshotVO>> cartItems(@RequestBody CartItemsRequest request) {
        return ApiResponse.ok(cartCheckoutService.items(request.memberId(), nullSafe(request.itemIds())));
    }

    /**
     * 结算闸门第一阶段（幂等领取）：清空这些明细并返回领取结果。
     *
     * <p>同一 {@code orderNo} 重放 → {@code claimed=true}（回放当时存下的明细快照），
     * 不同 {@code orderNo} 抢同一批明细 → 只有一方拿到。
     */
    @PostMapping("/cart/claim")
    public ApiResponse<CartClaimResultVO> claimCartItems(@RequestBody CartClaimRequest request) {
        return ApiResponse.ok(cartCheckoutService.claim(request.memberId(), request.orderNo(),
                nullSafe(request.itemIds())));
    }

    /**
     * 结算闸门第二阶段（补偿归还）：订单事务回滚时把已领取的明细还回购物车。
     *
     * <p>幂等：第二次调用返回 {@code false}（没有领取记录了），不会重复归还。
     */
    @PostMapping("/cart/restore")
    public ApiResponse<Boolean> restoreCartItems(@RequestBody CartRestoreRequest request) {
        return ApiResponse.ok(cartCheckoutService.restore(request.memberId(), request.orderNo()));
    }

    private static List<Long> nullSafe(List<Long> ids) {
        return ids == null ? List.of() : ids;
    }
}
