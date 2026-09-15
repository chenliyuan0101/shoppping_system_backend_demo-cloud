package com.mall.trade.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 某个会员的订单口径摘要（域间契约）：订单数 + 累计实付。
 *
 * <p>口径（与改造前后台会员详情完全一致）：
 * <ul>
 *   <li>{@code orderCount}：该会员的全部订单数（含已取消，按逻辑未删除计）；</li>
 *   <li>{@code paidAmount}：{@code pay_status = 1}（已支付）订单的 {@code pay_amount} 之和——
 *       <b>不含</b>已全额退款（{@code pay_status = 2}）的订单。</li>
 * </ul>
 * 注意它和看板"今日销售额"的口径不同（后者是 {@code pay_status IN (1,2)}），
 * 这是历史口径差异，P0 只搬位置不改口径。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberOrderBriefVO {

    private long orderCount;

    /** 累计实付（分） */
    private long paidAmount;
}
