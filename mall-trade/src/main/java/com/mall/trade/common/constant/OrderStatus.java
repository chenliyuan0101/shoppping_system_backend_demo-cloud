package com.mall.trade.common.constant;

/**
 * 订单主状态 {@code oms_order.order_status} 的唯一定义：
 * 0 待支付 / 1 待发货 / 2 待收货 / 3 已完成 / 4 已取消 / 5 已关闭 / 6 退款中 / 7 已退款。
 *
 * <p>状态流转一律用条件 UPDATE(CAS) 实现，禁止先查后改；文本文案见 {@link OrderTexts#orderStatus(Integer)}。
 */
public final class OrderStatus {

    public static final int WAIT_PAY = 0;
    public static final int WAIT_SHIP = 1;
    public static final int WAIT_RECEIVE = 2;
    public static final int FINISHED = 3;
    public static final int CANCELED = 4;
    public static final int CLOSED = 5;
    /** 售后处理中(退货退款已同意，等待买家回寄) */
    public static final int REFUNDING = 6;
    /** 已退款(仅退款成功 / 退货退款完成 / 后台关闭已支付单) */
    public static final int REFUNDED = 7;

    private OrderStatus() {
    }
}
