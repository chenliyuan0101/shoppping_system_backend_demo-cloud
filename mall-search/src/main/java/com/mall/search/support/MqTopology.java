package com.mall.search.support;

/**
 * MQ 拓扑"词汇表"：交换机 / 队列 / 路由键的名字（**本服务只持有商品索引同步这一套**）。
 *
 * <p><b>为什么单独抽出来</b>：这些名字是消息生产方与消费方之间的<b>契约</b>，
 * 但它们的载体是 {@code static final String} 常量——javac 会把它<b>内联</b>进调用方字节码，
 * 于是"业务域引用组装根"这件事在字节码层完全看不见（架构闸门实测踩到过，见《微服务改造方案.md》附录 D.1）。
 * 把契约放进共享内核、把 {@code @Bean} 声明留在各自服务的组装根，边界才立得住：
 * 业务代码只依赖 {@code MqTopology}，{@code com.mall.search.config.RabbitMqConfig} 只依赖它、不被它依赖。
 *
 * <p><b>⚠️ 名字一个字都不许改</b>（P6-2 规格 §1.2/§3）：
 * 单体（过渡期的生产方/消费方）与其它服务声明的就是这些名字与参数。
 * 改名要"两级同时改"，而交换机/队列的**声明参数必须与已在 broker 上的定义一致**
 * ——P5 踩过 {@code PRECONDITION_FAILED}（声明参数不一致时 broker 直接拒绝声明）。
 * 语义完全一样（"这些 spuId / 这个品牌的索引需要重算"），改名收益为零、风险非零。
 * <b>不要</b>新造 {@code product.changed}：那是 P6-5 的事，本批不动单体的 ES 代码。
 *
 * <p><b>拓扑结构</b>（商品索引同步 = 工作队列 + 重试队列 + 死信队列）：
 * <pre>
 *   投递(mall.pms.sync, direct) ──product.sync──► [mall.pms.es-sync] ──► ProductSyncConsumer
 *                                                     │ 消费失败：转投重试队列(每条消息自带 TTL)
 *                                                     ▼
 *                                     ──product.sync.retry──► [mall.pms.es-sync.retry]
 *                                                     │ TTL 到期 → 死信回工作队列(product.sync)
 *                                                     ▼
 *                                     ──product.sync.dlq────► [mall.pms.es-sync.dlq]（观测/人工处理）
 * </pre>
 *
 * <p>⚠️ 与 {@code CacheKeys} 同类：本类只放"跨服务共享的常量"，不放任何逻辑，也不持有 Spring Bean。
 * ⚠️ 与单体的 {@code com.mall.demo.common.MqTopology} 相比，这里**只保留 SYNC_ 这一组**
 * （订单超时关单 / 领域事件那两组属 trade 与各事件消费方）——与 mall-marketing、mall-review 的
 * 同一口径：每个服务自持副本、只留自己用得到的。
 */
public final class MqTopology {

    private MqTopology() {
    }

    // ==================== 商品索引同步(mall.pms.sync) ====================

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
}
