package com.mall.usercenter.support;

/**
 * MQ 拓扑"词汇表"：交换机 / 队列 / 路由键的名字。
 *
 * <p>这是 P3-5 从单体共享内核（{@code com.mall.demo.common.MqTopology}）复制过来的<b>契约副本</b>，
 * 与本服务其它契约副本（{@code support/dto/MemberBriefVO} 等）同一套取舍：
 * 跨服务共享 jar 会把"各自的演进"重新绑死，契约靠"名字逐字相同"守，而不是靠同一个 class 文件。
 *
 * <h2>扇出，而不是竞争消费（P3-5 最关键的一条）</h2>
 * 单体（发布方，{@code oms.mq.OrderEventPublisher}）把领域事件投到 topic 交换机
 * {@link #EVENT_EXCHANGE}（{@code mall.oms.event}），路由键 {@link #EVENT_ROUTING_PAID} /
 * {@link #EVENT_ROUTING_SHIPPED} / {@link #EVENT_ROUTING_REFUND}。
 * 本服务声明<b>自己的</b>队列 {@link #NOTIFICATION_QUEUE}（{@code mall.user.notification}）与自己的
 * 死信/重试队列，绑在<b>同一个</b>交换机上——同一条事件因此同时进入两个服务的队列，
 * 各写各的副作用（单体只重算订单日统计，本服务只写站内消息）。
 *
 * <p><b>为什么不能复用单体那个队列名（{@code mall.oms.events}）</b>：同名队列 = 竞争消费，
 * 一条消息只会被其中一个服务拿到，结果是"通知随机少一半"或"统计随机少一半"，
 * 而且现象随机、难以复现。拓扑里的队列名就是"谁是消费者"的边界，不能省。
 *
 * <p><b>为什么队列/交换机的声明参数必须与单体完全一致</b>（durable / autoDelete / arguments）：
 * {@link #EVENT_EXCHANGE} 由两个服务各自声明一次，RabbitMQ 以<b>第一次</b>声明的参数为准，
 * 后到的声明只要有一点不同就报 {@code PRECONDITION_FAILED}（经典事故）。因此本服务声明它是
 * {@code ExchangeBuilder.topicExchange(...).durable(true)}，与单体一字不差；绑定（Binding）不属于
 * 声明参数，可以各自增删——扇出正是靠新增绑定实现的。
 *
 * <pre>
 * 发布方（单体 oms）                      交换机                       消费方
 * ──── order.paid ────────────┐
 * ──── order.shipped ─────────┤   mall.oms.event (topic)  ──┬──► [mall.oms.events]            → 单体：重算订单日统计
 * ──── refund.settled ────────┘                             └──► [mall.user.notification]     → 本服务：写站内消息
 * </pre>
 *
 * <p>⚠️ 与 {@code CacheKeys} 同类：本类只放"跨服务共享的常量"，不放任何逻辑，也不持有 Spring Bean
 * （Bean 在组装根 {@code com.mall.usercenter.config.RabbitMqConfig}）。
 */
public final class MqTopology {

    private MqTopology() {
    }

    // ==================== 1) 领域事件交换机（与单体共享，声明参数必须一致） ====================

    /** 领域事件交换机(topic)：支付成功 / 已发货 / 退款到账。**由单体与本服务各自声明**，参数必须相同 */
    public static final String EVENT_EXCHANGE = "mall.oms.event";

    public static final String EVENT_ROUTING_PAID = "order.paid";
    public static final String EVENT_ROUTING_SHIPPED = "order.shipped";
    public static final String EVENT_ROUTING_REFUND = "refund.settled";

    // ==================== 2) 本服务的通知队列（P3-5 新增，单体不声明、不使用） ====================

    /** 通知工作队列：{@link #EVENT_ROUTING_PAID} / {@link #EVENT_ROUTING_SHIPPED} / {@link #EVENT_ROUTING_REFUND} 三个业务键 */
    public static final String NOTIFICATION_QUEUE = "mall.user.notification";
    /** 通知重试队列：没有消费者，消息按自身 TTL 等待后死信回工作队列（路由键 {@link #NOTIFICATION_ROUTING_REDELIVER}） */
    public static final String NOTIFICATION_RETRY_QUEUE = "mall.user.notification.retry";
    /** 通知死信队列：重试超限（或正文无法解析）的消息落这里，仅用于观测与人工重投 */
    public static final String NOTIFICATION_DLQ = "mall.user.notification.dlq";

    /**
     * 本服务自己的路由键：刻意<b>不与单体的 {@code event.retry} / {@code event.redeliver} / {@code event.dlq} 同名</b>。
     *
     * <p>链路一模一样（失败 → 重试队列等 TTL → 回工作队列 → 超限进死信），但名字必须各自独立：
     * 若共用 {@code event.dlq}，两边死信队列会同时绑在同一个键上，任何一方的死信都会扇出到<b>双方</b>的
     * 死信队列（观测串味、人工重投可能重投别人的消息）。
     */
    public static final String NOTIFICATION_ROUTING_RETRY = "notification.retry";
    /** 只绑本服务的工作队列：重试队列 TTL 到期后按这个键回到工作队列 */
    public static final String NOTIFICATION_ROUTING_REDELIVER = "notification.redeliver";
    /** 只绑本服务的死信队列 */
    public static final String NOTIFICATION_ROUTING_DLQ = "notification.dlq";
}
