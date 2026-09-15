package com.mall.trade.internal;

import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.dto.MemberOrderBriefVO;
import com.mall.trade.common.dto.OrderSummaryVO;
import com.mall.trade.common.dto.OrderTrendPointVO;
import com.mall.trade.common.dto.SpuAmountVO;
import com.mall.trade.oms.service.OrderStatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.mall.common.support.MemberId;

/**
 * 交易域的内部接口：看板统计（概览/趋势/榜单/会员摘要）。
 *
 * <p><b>P4 批次 3：评价场景的两条内部端点已删除</b>
 * （{@code GET /internal/v1/order/comment/context} + {@code POST /internal/v1/order/comment/claim}）。
 * 它们原本是"评价侧跨域读订单 + 跨域抢评价标记"的入口，评价域搬去 {@code mall-review} 之后，
 * 提交评价的归属/状态/期限/防重全部在 review 自己的 {@code review_pending_item} 上判定，
 * 这两条端点<b>没有任何调用方了</b>——留着等于把"订单域可以被别人写一个评价标记"这个能力敞着。
 * 相应地，{@code oms_order_item.comment_status} 也停止被写入（列保留，见 {@code OrderItem}）。
 *
 * <p>统计这几条是"看板/趋势/榜单"的读路径，调用方（将来的 admin BFF）应当并发调用并允许降级。
 *
 * <p><b>P7（本批）新增一条</b>：{@code POST /stat/member-order-brief/batch}——后台会员列表的
 * "一次批量补数"。它是本批唯一动到单体的一处，且**只增不减、只在 {@code /internal/**} 下**：
 * 对外（{@code /api/**}）的任何路径、参数、响应字段与文案一律未动（C1 不受影响，见 P7 任务书硬约束）。
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalTradeController {

    private final OrderStatQueryService orderStatQueryService;

    /** 看板概览（今日下单/销售额/待发货/待处理退款） */
    @GetMapping("/stat/summary")
    public ApiResponse<OrderSummaryVO> statSummary() {
        return ApiResponse.ok(orderStatQueryService.summary());
    }

    /** 近 N 天趋势（连续且补零，按日期升序） */
    @GetMapping("/stat/trend")
    public ApiResponse<List<OrderTrendPointVO>> statTrend(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(orderStatQueryService.trend(days));
    }

    /** 销售额榜（有序，商品可能已删除） */
    @GetMapping("/stat/top-amount")
    public ApiResponse<List<SpuAmountVO>> statTopAmount(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(orderStatQueryService.topPaidAmountBySpu(limit));
    }

    /** 某会员的订单口径摘要（订单数 + 累计实付） */
    @GetMapping("/stat/member-order-brief")
    public ApiResponse<MemberOrderBriefVO> memberOrderBrief(@RequestParam Long memberId) {
        return ApiResponse.ok(orderStatQueryService.memberOrderBrief(memberId));
    }

    /** 批量取会员 id（**批量形状照 {@code /internal/v1/user/member/batch}**：请求体是 {@code {"memberIds":[...]}}） */
    public record MemberIdsRequest(List<Long> memberIds) {
    }

    /**
     * <b>批量</b>会员订单口径摘要（P7 新增，见 {@code OrderStatQueryService#memberOrderBriefs}）。
     *
     * <p>服务对象是 {@code mall-admin} 的后台会员列表：它拿到当页会员 id 后必须**一次**取回补数
     * （规格 §4 的可执行判据是"远程调用次数与页码无关"）。没有这条端点，列表只能逐个会员调上面那条
     * GET ⇒ 每页 {@code 2 × pageSize} 次查询，正是要禁止的 N+1。
     *
     * <p>响应是 {@code {会员id: {orderCount, paidAmount}}}（JSON 的 key 必然是字符串，
     * 与 {@code /internal/v1/product/sku/min-price/batch} 的 {@code Map<Long,...>} 形状同一惯例）。
     * 请求的每个 id 都会有值；无订单的会员给 {@code {orderCount:0, paidAmount:0}}。
     *
     * <p>⚠️ 这是 {@code /internal/**}（网关不路由、{@code X-Internal-Token} 把守），
     * 与对外接口零交集：**没有**触碰任何 {@code /api/**} 行为（P7 任务书的 C1 硬约束）。
     */
    @PostMapping("/stat/member-order-brief/batch")
    public ApiResponse<Map<Long, MemberOrderBriefVO>> memberOrderBriefBatch(@RequestBody MemberIdsRequest request) {
        return ApiResponse.ok(orderStatQueryService.memberOrderBriefs(
                request == null || request.memberIds() == null ? List.of() : request.memberIds()));
    }
}
