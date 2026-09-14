package com.mall.demo.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 后台看板概览中的<b>订单口径</b>部分（域间契约）。
 *
 * <p>为什么单独一个 DTO：看板汇总跨越订单、商品、会员三个域，
 * 每个域只回答"自己那部分"的问题——订单域给这四项，商品域给在架数，会员域给会员总数，
 * 由 admin（组装根/BFF）合并。这样任何一个域的字段变化都只影响自己那份契约。
 *
 * <p>口径说明（与改造前完全一致）：
 * <ul>
 *   <li>{@code todayOrderCount}：按下单时间落在今天的订单数；</li>
 *   <li>{@code todaySalesAmount}：今天支付成功的销售额（{@code pay_status IN (1,2)}，含已退款）；</li>
 *   <li>{@code waitShipCount}：状态为待发货的订单数；</li>
 *   <li>{@code refundPendingCount}：待处理的售后单数。</li>
 * </ul>
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
