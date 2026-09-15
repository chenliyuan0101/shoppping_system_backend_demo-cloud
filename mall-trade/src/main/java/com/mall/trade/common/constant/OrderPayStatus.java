package com.mall.trade.common.constant;

/**
 * 订单支付状态 {@code oms_order.pay_status}：0 未支付 / 1 已支付 / 2 已全额退款。
 *
 * <p>与 {@link PaymentStatus}({@code oms_payment.pay_status}) 是两列两套取值，不要混用：
 * 统计口径「已支付」用 {@code pay_status >= PAID}(含已退款)，见 {@code OrderDailyStatMapper}。
 */
public final class OrderPayStatus {

    public static final int UNPAID = 0;
    public static final int PAID = 1;
    /** 已全额退款(退款成功后由 {@code RefundServiceImpl} / 后台关闭已支付单写入) */
    public static final int REFUNDED = 2;

    private OrderPayStatus() {
    }
}
