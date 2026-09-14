package com.mall.marketing.support;

/**
 * MQ 拓扑"词汇表"：交换机 / 队列 / 路由键的名字（P5 步骤 E 新增）。
 *
 * <p>与 {@code mall-review} 的 {@code support/MqTopology}、{@code mall-user-center} 的同名类是同一套做法：
 * <b>从单体共享内核复制的契约副本</b>——跨服务共享 jar 会把各自的演进重新绑死，
 * 契约靠"名字逐字相同"守，而不是靠同一个 class 文件。
 *
 * <h2>扇出，而不是竞争消费</h2>
 * 单体（发布方，{@code oms.mq.OrderEventPublisher#publishRawAfterCommit}）把"订单关闭"事件投到
 * topic 交换机 {@link #EVENT_EXCHANGE}（{@code mall.oms.event}），路由键
 * {@link #EVENT_ROUTING_CLOSED}（{@code order.closed}）。本服务声明<b>自己的</b>队列
 * {@link #MARKETING_QUEUE}（{@code mall.marketing.order-closed}）与自己的重试/死信队列，
 * 绑在<b>同一个</b>交换机上——同一条事件因此同时进入每一个声明了绑定的服务。
 *
 * <p><b>为什么不能复用 {@code mall.oms.events} / {@code mall.user.notification} /
 * {@code mall.review.order-finished}</b>：同名队列 = 竞争消费，一条消息只会被其中一个服务拿到，
 * 结果是"券偶尔没被兜底解锁"或"别人的业务少收一条事件"，而且现象随机、极难复现。
 * 队列名就是"谁是消费者"的边界。
 *
 * <p><b>为什么交换机声明参数必须与单体完全一致</b>：{@link #EVENT_EXCHANGE} 由多个服务各自声明一次，
 * RabbitMQ 以<b>第一次</b>声明的参数为准，后到者只要有一点不同就报 {@code PRECONDITION_FAILED}（经典事故）。
 * 实测该交换机是 {@code type=topic durable=true auto_delete=false internal=false arguments={}}，
 * 因此这里声明为 {@code new TopicExchange(EVENT_EXCHANGE, true, false)}，与单体/其它服务一字不差。
 * 绑定（Binding）不属于声明参数，可以各自增删——扇出正是靠新增绑定实现的。
 *
 * <pre>
 * 发布方（单体 oms）                      交换机                       消费方
 * ──── order.paid ─────────────┐
 * ──── order.shipped ──────────┤   mall.oms.event (topic)  ──┬──► [mall.oms.events]              → 单体：订单日统计
 * ──── refund.settled ─────────┤                             ├──► [mall.user.notification]       → user-center：站内消息
 * ──── order.finished ─────────┤                             ├──► [mall.review.order-finished]   → review：待评价投影
 * ──── order.closed ───────────┘                             └──► [mall.marketing.order-closed]  → 本服务：**券解锁兜底**
 * </pre>
 *
 * <p>⚠️ 本类只放"跨服务共享的常量"，不放逻辑、不持有 Spring Bean（Bean 在
 * {@code com.mall.marketing.config.RabbitMqConfig}）。
 */
public final class MqTopology {

    private MqTopology() {
    }

    // ==================== 1) 领域事件交换机（与单体共享，声明参数必须一致） ====================

    /** 领域事件交换机(topic)。**由各服务各自声明**，参数必须相同 */
    public static final String EVENT_EXCHANGE = "mall.oms.event";

    public static final String EVENT_ROUTING_PAID = "order.paid";
    public static final String EVENT_ROUTING_SHIPPED = "order.shipped";
    public static final String EVENT_ROUTING_REFUND = "refund.settled";
    public static final String EVENT_ROUTING_FINISHED = "order.finished";

    /**
     * 订单关闭（{@code order.closed}）——本服务用它做**券解锁的兜底**。
     *
     * <p>值必须与单体 {@code MqTopology.EVENT_ROUTING_CLOSED} 逐字一致（两边各自声明）。
     */
    public static final String EVENT_ROUTING_CLOSED = "order.closed";

    // ==================== 2) 本服务的券解锁兜底队列（P5 步骤 E 新增，别人不声明、不使用） ====================

    /** 工作队列：只绑 {@link #EVENT_ROUTING_CLOSED}（本服务只关心关单） */
    public static final String MARKETING_QUEUE = "mall.marketing.order-closed";
    /** 重试队列：没有消费者，消息按自身 TTL 等待后死信回工作队列（路由键 {@link #MARKETING_ROUTING_REDELIVER}） */
    public static final String MARKETING_RETRY_QUEUE = "mall.marketing.order-closed.retry";
    /** 死信队列：重试超限（或正文无法解析）的消息落这里，仅用于观测与人工重投 */
    public static final String MARKETING_DLQ = "mall.marketing.order-closed.dlq";

    /**
     * 本服务自己的路由键：刻意<b>不与单体的 {@code event.retry}/{@code event.redeliver}/{@code event.dlq}、
     * 也不与 review 的 {@code review.*} / user-center 的 {@code notification.*} 同名</b>。
     *
     * <p>链路一样（失败 → 重试队列等 TTL → 回工作队列 → 超限进死信），但名字必须各自独立：
     * 若共用 {@code event.dlq}，两边死信队列会同时绑在同一个键上，任何一方的死信都会扇出到<b>双方</b>
     * 的死信队列（观测串味，人工重投可能重投别人的消息）。
     */
    public static final String MARKETING_ROUTING_RETRY = "marketing.retry";
    /** 只绑本服务的工作队列：重试队列 TTL 到期后按这个键回到工作队列 */
    public static final String MARKETING_ROUTING_REDELIVER = "marketing.redeliver";
    /** 只绑本服务的死信队列 */
    public static final String MARKETING_ROUTING_DLQ = "marketing.dlq";
}
