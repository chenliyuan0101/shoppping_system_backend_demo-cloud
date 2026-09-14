package com.mall.demo.common;

/**
 * MQ 拓扑"词汇表"：交换机 / 队列 / 路由键的名字。
 *
 * <p><b>为什么单独抽出来</b>：这些名字是消息生产方与消费方之间的<b>契约</b>，
 * 但它们的载体是 {@code static final String} 常量——javac 会把它<b>内联</b>进调用方字节码，
 * 于是"业务域引用组装根"这件事在字节码层完全看不见（架构闸门实测踩到过，见《微服务改造方案.md》附录 D.1）。
 * 把契约放进共享内核、把 {@code @Bean} 声明留在各自服务的组装根，边界才立得住：
 * 业务代码只依赖 {@code MqTopology}，{@code mall.app.RabbitMqConfig} 只依赖它、不被它依赖。
 *
 * <p><b>拓扑结构</b>（三套，都是"工作队列 + 重试 + 死信"，差别只在线路）：
 * <pre>
 * 1) 订单超时关单（延迟消息 TTL + 死信，不依赖 rabbitmq_delayed_message_exchange 插件）
 *    下单(事务提交后)                                   到期(消息 TTL 耗尽)              消费失败
 *    ─────────────► mall.oms.delay ──► [order-timeout.delay] ──┐
 *                     (direct 交换机)      (无消费者,只等过期)     │ 死信
 *                                                               ▼
 *                                      mall.oms.direct ──► [mall.oms.order-timeout] ──► OrderTimeoutConsumer
 *                                                             │  nack(requeue=false)
 *                                                             ▼
 *                                                        [mall.oms.order-timeout.dlq]
 * 2) 商品索引同步（mall.pms.sync）：工作队列 → 重试队列(每条消息自带 TTL) → 死信队列
 * 3) 领域事件（mall.oms.event，topic）：order.paid / order.shipped / refund.settled
 * </pre>
 *
 * <p>⚠️ 与 {@link MallTime}、{@link CacheKeys} 同类：本类只放"跨服务共享的常量"，不放任何逻辑，
 * 也不持有 Spring Bean（Bean 在组装根 {@code com.mall.demo.app.RabbitMqConfig}）。
 */
public final class MqTopology {

    private MqTopology() {
    }

    // ==================== 1) 订单超时关单 ====================

    /** 延迟投递入口交换机 */
    public static final String DELAY_EXCHANGE = "mall.oms.delay";
    /** 延迟队列(无消费者，等消息 TTL 到期后死信出去) */
    public static final String DELAY_QUEUE = "mall.oms.order-timeout.delay";
    public static final String DELAY_ROUTING_KEY = "order.timeout.delay";

    /** 工作量交换机(延迟消息到期后的落地入口，也是消费入口) */
    public static final String WORK_EXCHANGE = "mall.oms.direct";
    /** 关单消费队列 */
    public static final String WORK_QUEUE = "mall.oms.order-timeout";
    public static final String WORK_ROUTING_KEY = "order.timeout";

    /** 消费失败的死信队列(无消费者，仅用于观测与人工处理) */
    public static final String DLQ = "mall.oms.order-timeout.dlq";
    public static final String DLQ_ROUTING_KEY = "order.timeout.dlq";

    // ==================== 2) 商品索引同步(mall.pms.sync) ====================

    /** 商品索引同步交换机(工作队列、重试队列、死信队列都挂在它上面) */
    public static final String SYNC_EXCHANGE = "mall.pms.sync";
    /** 同步工作队列：消费端调用 ES 写/删文档 */
    public static final String SYNC_QUEUE = "mall.pms.es-sync";
    public static final String SYNC_ROUTING_KEY = "product.sync";
    /** 重试队列：消息在这里 TTL 到期后回到工作队列(每条消息自带 TTL，见 Publisher) */
    public static final String SYNC_RETRY_QUEUE = "mall.pms.es-sync.retry";
    public static final String SYNC_RETRY_ROUTING_KEY = "product.sync.retry";
    /** 同步失败次数超限后的死信队列 */
    public static final String SYNC_DLQ = "mall.pms.es-sync.dlq";
    public static final String SYNC_DLQ_ROUTING_KEY = "product.sync.dlq";

    // ==================== 3) 领域事件(mall.oms.event) ====================

    /** 领域事件交换机(topic)：支付成功 / 已发货 / 退款到账 */
    public static final String EVENT_EXCHANGE = "mall.oms.event";
    /** 事件消费队列(一个消费者，两个副作用：站内消息 + 当日统计重算) */
    public static final String EVENT_QUEUE = "mall.oms.events";
    /** 重试队列：消息按自身 TTL 等待后死信回工作队列(路由键 EVENT_ROUTING_REDELIVER) */
    public static final String EVENT_RETRY_QUEUE = "mall.oms.events.retry";
    /** 事件死信队列 */
    public static final String EVENT_DLQ = "mall.oms.events.dlq";

    public static final String EVENT_ROUTING_PAID = "order.paid";
    public static final String EVENT_ROUTING_SHIPPED = "order.shipped";
    public static final String EVENT_ROUTING_REFUND = "refund.settled";
    /** 只绑到重试队列：失败消息先来这里等 TTL */
    public static final String EVENT_ROUTING_RETRY = "event.retry";
    /** 只绑到工作队列：重试队列 TTL 到期后按这个键回到工作队列 */
    public static final String EVENT_ROUTING_REDELIVER = "event.redeliver";
    /**
     * P4：确认收货（order.finished）——评价域用它建"待评价"读模型。
     *
     * <p>语义与既有三条事件不同：它带**明细快照**（orderItemId/spuId/skuId/spuTitle/skuImage/quantity），
     * 因此载荷是 {@code OrderFinishedMessage} 而不是 {@code OrderEventMessage}——
     * 后者被 paid/shipped/refund 三条共用，改它的形状会直接影响 user-center 通知消费者的反序列化。
     */
    public static final String EVENT_ROUTING_FINISHED = "order.finished";

    /**
     * P5 步骤 E：订单关闭（order.closed）——**券解锁的兜底**。
     *
     * <p>为什么关单已经有同步 {@code unlock} 了还要发事件：同步调用只在"关单这条路径真的被走到、
     * 且目标服务当时可达"时才有效。事件是**第二道防线**：事务提交后投递，marketing 消费后
     * 再解锁一次（幂等）；即使第一次调用失败（服务重启/网络抖动），事件到达后仍能纠正。
     * 第三道防线是营销域的每日对账（按 {@code lock_time} 扫"锁太久"的券）。
     *
     * <p>载荷是 {@code OrderClosedMessage}（含 couponMemberId，可能为 null），
     * 与既有三条事件载荷不同——同 {@link #EVENT_ROUTING_FINISHED} 的理由，不共用形状。
     */
    public static final String EVENT_ROUTING_CLOSED = "order.closed";

    public static final String EVENT_ROUTING_DLQ = "event.dlq";    /** 只绑到死信队列 */
}
