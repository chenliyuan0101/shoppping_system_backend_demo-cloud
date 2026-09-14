package com.mall.review.support;

/**
 * MQ 拓扑"词汇表"：交换机 / 队列 / 路由键的名字。
 *
 * <p>这是 P4-2 从单体共享内核（{@code com.mall.demo.common.MqTopology}）复制过来的<b>契约副本</b>，
 * 与 user-center 的 {@code support/MqTopology.java} 是同一套取舍（那里是 P3-5 建的）：
 * 跨服务共享 jar 会把"各自的演进"重新绑死，契约靠"名字逐字相同"守，而不是靠同一个 class 文件。
 *
 * <h2>扇出，而不是竞争消费（P4-2 最关键的一条）</h2>
 * 单体（发布方，{@code oms.mq.OrderEventPublisher#publishRawAfterCommit}）在 {@code OrderServiceImpl.confirm}
 * 里把确认收货事件投到 topic 交换机 {@link #EVENT_EXCHANGE}（{@code mall.oms.event}），
 * 路由键 {@link #EVENT_ROUTING_FINISHED}（{@code order.finished}）。
 * 本服务声明<b>自己的</b>队列 {@link #REVIEW_QUEUE}（{@code mall.review.order-finished}）与自己的
 * 重试/死信队列，绑在<b>同一个</b>交换机上——同一条事件因此同时进入每一个声明了绑定的服务，
 * 各写各的副作用（本服务只投影 {@code review_pending_item}）。
 *
 * <p><b>为什么不能复用别人的队列名（{@code mall.oms.events} / {@code mall.user.notification}）</b>：
 * 同名队列 = 竞争消费，一条消息只会被其中一个服务拿到，结果是"待评价列表随机少一半"
 * 或"站内消息随机少一半"，而且现象随机、难以复现。拓扑里的队列名就是"谁是消费者"的边界。
 * 实测证据（P4-2 施工时的 RabbitMQ 管理 API）：{@code mall.oms.event} 上已有
 * {@code mall.oms.events} 与 {@code mall.user.notification} 两条**不同队列**的绑定，
 * 且 {@code order.finished} 当时**没有任何**绑定——本服务的绑定是新增的第三条队列边，谁都不抢。
 *
 * <p><b>为什么交换机/队列的声明参数必须与单体完全一致</b>（durable / autoDelete / arguments）：
 * {@link #EVENT_EXCHANGE} 由多个服务各自声明一次，RabbitMQ 以<b>第一次</b>声明的参数为准，
 * 后到的声明只要有一点不同就报 {@code PRECONDITION_FAILED}（经典事故）。实测该交换机现在是
 * {@code type=topic durable=true auto_delete=false internal=false arguments={}}，
 * 因此本服务声明它是 {@code topicExchange(...).durable(true)}（无 arguments），与单体一字不差；
 * 绑定（Binding）不属于声明参数，可以各自增删——扇出正是靠新增绑定实现的。
 *
 * <pre>
 * 发布方（单体 oms）                      交换机                       消费方
 * ──── order.paid ─────────────┐
 * ──── order.shipped ──────────┤   mall.oms.event (topic)  ──┬──► [mall.oms.events]            → 单体：重算订单日统计
 * ──── refund.settled ─────────┤                             ├──► [mall.user.notification]     → user-center：写站内消息
 * ──── order.finished ─────────┘                             └──► [mall.review.order-finished] → 本服务：投影待评价读模型
 * </pre>
 *
 * <p>⚠️ 与 {@code CacheKeys} 同类：本类只放"跨服务共享的常量"，不放任何逻辑，也不持有 Spring Bean
 * （Bean 在组装根 {@code com.mall.review.config.RabbitMqConfig}）。
 */
public final class MqTopology {

    private MqTopology() {
    }

    // ==================== 1) 领域事件交换机（与单体共享，声明参数必须一致） ====================

    /** 领域事件交换机(topic)：支付成功 / 已发货 / 退款到账 / 确认收货。**由各服务各自声明**，参数必须相同 */
    public static final String EVENT_EXCHANGE = "mall.oms.event";

    public static final String EVENT_ROUTING_PAID = "order.paid";
    public static final String EVENT_ROUTING_SHIPPED = "order.shipped";
    public static final String EVENT_ROUTING_REFUND = "refund.settled";

    /**
     * P4-2：确认收货（order.finished）——本服务用它建"待评价"读模型。
     *
     * <p>与既有三条事件的差别在<b>载荷形状</b>：这条带明细快照
     * （{@code orderItemId/spuId/skuId/spuTitle/skuImage/quantity}），对应
     * {@code com.mall.review.mq.OrderFinishedMessage} 而不是 {@code OrderEventMessage}。
     * 值必须与单体 {@code MqTopology.EVENT_ROUTING_FINISHED} 逐字一致（两边各自声明）。
     */
    public static final String EVENT_ROUTING_FINISHED = "order.finished";

    // ==================== 2) 本服务的待评价投影队列（P4-2 新增，别人不声明、不使用） ====================

    /** 待评价投影工作队列：只绑 {@link #EVENT_ROUTING_FINISHED} 一个业务键（本服务只关心确认收货） */
    public static final String REVIEW_QUEUE = "mall.review.order-finished";
    /** 投影重试队列：没有消费者，消息按自身 TTL 等待后死信回工作队列（路由键 {@link #REVIEW_ROUTING_REDELIVER}） */
    public static final String REVIEW_RETRY_QUEUE = "mall.review.order-finished.retry";
    /** 投影死信队列：重试超限（或正文无法解析）的消息落这里，仅用于观测与人工重投 */
    public static final String REVIEW_DLQ = "mall.review.order-finished.dlq";

    /**
     * 本服务自己的路由键：刻意<b>不与单体的 {@code event.retry} / {@code event.redeliver} / {@code event.dlq}
     * 也不与 user-center 的 {@code notification.*} 同名</b>。
     *
     * <p>链路与它们一模一样（失败 → 重试队列等 TTL → 回工作队列 → 超限进死信），但名字必须各自独立：
     * 若共用 {@code event.dlq}，两边死信队列会同时绑在同一个键上，任何一方的死信都会扇出到<b>双方</b>的
     * 死信队列（观测串味、人工重投可能重投别人的消息）。
     */
    public static final String REVIEW_ROUTING_RETRY = "review.retry";
    /** 只绑本服务的工作队列：重试队列 TTL 到期后按这个键回到工作队列 */
    public static final String REVIEW_ROUTING_REDELIVER = "review.redeliver";
    /** 只绑本服务的死信队列 */
    public static final String REVIEW_ROUTING_DLQ = "review.dlq";
}
