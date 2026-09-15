package com.mall.usercenter.mq;

import com.mall.common.support.MemberId;

/**
 * 订单/售后领域事件消息(JSON 序列化)——单体的 {@code com.mall.demo.oms.mq.OrderEventMessage} 的契约副本。
 *
 * <p><b>字段名与顺序必须与单体逐字相同</b>：JSON 字段名来自记录组件名，改一个字等于改契约，
 * 发布会成功、消费端解析失败（消息直接进死信队列）。两侧的字段名由
 * 《后端RabbitMQ使用手册.md》§9 与本服务 {@code NotificationEventMqMySqlTest} 守着。
 *
 * <p>事件是**广播性质的"发生了什么"**，不是"要求谁做什么"：消费端各自决定要不要处理
 * （单体：重算订单日统计；本服务：写站内消息）。因此本服务的加入对发布方完全透明——
 * 单体不需要改一行代码，只是在交换机上多了一条绑定。
 *
 * @param eventType 事件类型：{@link #TYPE_ORDER_PAID} / {@link #TYPE_ORDER_SHIPPED} /
 *                  {@link #TYPE_REFUND_SETTLED} / {@link #TYPE_ORDER_FINISHED}
 * @param orderNo   订单号（同时是站内消息的业务单号 biz_no，幂等键的一半）
 * @param memberId  会员 id（站内消息的接收者）；null 时本服务不产生消息
 * @param amount    金额(分)：支付金额 / 退款金额，发货事件无金额
 * @param remark    附加说明（如物流公司 + 单号），用于拼通知文案
 * @param atMillis  事件发生时间(毫秒)；单体用它定位统计日期，本服务不消费该字段
 */
public record OrderEventMessage(String eventType, String orderNo, Long memberId, Long amount,
                                String remark, long atMillis) {

    public static final String TYPE_ORDER_PAID = "ORDER_PAID";
    public static final String TYPE_ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String TYPE_REFUND_SETTLED = "REFUND_SETTLED";
    /**
     * P4-2：确认收货（与单体 {@code OrderFinishedMessage.TYPE_ORDER_FINISHED} 逐字同值）。
     *
     * <p>只作为"词汇表"的一部分登记在这里：本服务对该事件<b>没有副作用</b>（站内消息没有这个类型），
     * 而且它的<b>载荷形状也不同</b>（{@code {orderNo, memberId, finishedTime, items[]}}，没有 eventType），
     * 由 trade 直接发 {@code mall.review.order-finished} 队列，本服务的队列没有这个绑定。
     * 登记它是为了让"收到这个类型怎么办"在代码里是<b>显式的一支</b>，而不是掉进
     * {@code NotificationEventConsumer} 的 default 分支刷 WARN（见那里的 switch）。
     */
    public static final String TYPE_ORDER_FINISHED = "ORDER_FINISHED";

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
