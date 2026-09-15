package com.mall.trade.oms.mq;

import com.mall.common.support.MemberId;

/**
 * 订单/售后领域事件消息(JSON 序列化)。
 *
 * <p>事件是**广播性质的"发生了什么"**，不是"要求谁做什么"：消费端各自决定要不要处理
 * （当前是"写一条站内消息 + 重算当日统计"）。因此加新消费者不需要改发布方。
 *
 * @param eventType 事件类型：{@link #TYPE_ORDER_PAID} / {@link #TYPE_ORDER_SHIPPED} / {@link #TYPE_REFUND_SETTLED}
 * @param orderNo   订单号
 * @param memberId  会员 id（站内消息的接收者）
 * @param amount    金额(分)：支付金额 / 退款金额，发货事件无金额
 * @param remark    附加说明（如物流公司 + 单号），用于拼通知文案
 * @param atMillis  事件发生时间(毫秒)
 */
public record OrderEventMessage(String eventType, String orderNo, Long memberId, Long amount,
                                String remark, long atMillis) {

    public static final String TYPE_ORDER_PAID = "ORDER_PAID";
    public static final String TYPE_ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String TYPE_REFUND_SETTLED = "REFUND_SETTLED";

    public static OrderEventMessage paid(String orderNo, Long memberId, Long amount) {
        return new OrderEventMessage(TYPE_ORDER_PAID, orderNo, memberId, amount, null, System.currentTimeMillis());
    }

    public static OrderEventMessage shipped(String orderNo, Long memberId, String logistics) {
        return new OrderEventMessage(TYPE_ORDER_SHIPPED, orderNo, memberId, null, logistics, System.currentTimeMillis());
    }

    public static OrderEventMessage refundSettled(String orderNo, Long memberId, Long amount) {
        return new OrderEventMessage(TYPE_REFUND_SETTLED, orderNo, memberId, amount, null, System.currentTimeMillis());
    }
}
