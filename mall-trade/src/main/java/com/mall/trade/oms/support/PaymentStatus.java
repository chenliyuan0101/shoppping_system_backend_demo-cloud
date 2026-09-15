package com.mall.trade.oms.support;

/**
 * 支付流水状态 {@code oms_payment.pay_status}：0 失败(模拟失败) / 1 成功 / 2 已全额退回。
 *
 * <p>与 {@link OrderPayStatus}({@code oms_order.pay_status}) 是两列两套取值，不要混用。
 */
public final class PaymentStatus {

    public static final int FAILED = 0;
    public static final int SUCCESS = 1;
    public static final int REFUNDED = 2;

    private PaymentStatus() {
    }
}
