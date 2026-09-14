package com.mall.admin.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 看板概览中的<b>订单口径</b>部分：单体 {@code com.mall.demo.common.dto.OrderSummaryVO} 的契约快照，
 * 也是单体 {@code GET /internal/v1/stat/summary} 的响应体。
 *
 * <p>口径（与改造前完全一致，由订单域定义）：
 * <ul>
 *   <li>{@code todayOrderCount}：按下单时间落在今天的订单数；</li>
 *   <li>todaySalesAmount}：今天支付成功的销售额（{@code pay_status IN (1,2)}，含已退款）；</li>
 *   <li>{@code waitShipCount}：状态为待发货的订单数；</li>
 *   <li>{@code refundPendingCount}：待处理的售后单数。</li>
 * </ul>
 * ⚠️ 四个都是原始类型 {@code long}：**跨进程契约里它们永远不会是 null**，
 * 因此看板降级时"缺的那部分"填 0 是安全的（不需要区分"没有数据"与"没取到"——
 * 这一层区分由降级日志承担）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderSummaryVO {

    private long todayOrderCount;

    private long todaySalesAmount;

    private long waitShipCount;

    private long refundPendingCount;
}
