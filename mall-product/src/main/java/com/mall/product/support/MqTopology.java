package com.mall.product.support;

/**
 * MQ 拓扑"词汇表"（**生产方视角**）：只放"跨服务共享的常量"，不放逻辑、不持有 Spring Bean。
 *
 * <h2>为什么每个服务各持一份副本</h2>
 * 这些名字（交换机/队列/路由键）是生产方与消费方之间的<b>契约</b>，但载体是 {@code static final String}
 * ——javac 会把它<b>内联</b>进调用方字节码，于是"业务域引用了别的服务的类"这件事在字节码层完全看不见
 * （架构闸门踩过，见《微服务改造方案.md》附录 D.1）。所以约定：**契约放本服务的 support，{@code @Bean}
 * 声明留在各自服务的组装根**，业务代码只依赖本类。与 {@code mall-search}、{@code mall-marketing}、
 * {@code mall-review} 是同一口径（每个服务自持副本、只留自己用得到的）。
 *
 * <h2>⚠️ 名字一个字都不许改（P6-2 规格 §1.2/§3）</h2>
 * 改名要"两级同时改"，而交换机/队列的<b>声明参数必须与 broker 上已有的定义一致</b>
 * ——若不一致，broker 直接拒绝声明（P5 踩过 {@code PRECONDITION_FAILED}）。语义完全一样
 * （"这些 spuId / 这个品牌的索引需要重算"），改名收益为零、风险非零。
 *
 * <h2>P6-5 #1：本服务**只做生产方**</h2>
 * 背景：P6-4 把商品域的真值搬到 {@code mall_product} 后，本服务是唯一的商品写方，但当时
 * **没有发布方**：库存/销量变化只能写本地 Redis 待同步集合，靠 {@code mall-search} 的定时任务
 * 15s 收敛（单体时代是 MQ 亚秒级）。本批把发布方补回本服务（方案 §2.6 的既定形状：
 * **MQ 主 + Redis 兜底**，与单体时代的 {@code ProductSearchServiceImpl#markDirty} 逐字同语义）。
 *
 * <p>因此本类**只保留 SYNC_ 这一组**：重试/DLQ 的投递是<b>消费方</b>的事（本服务不消费该队列，
 * 见 {@code mall-search} 的 {@code ProductSyncConsumer}）。
 *
 * <pre>
 *   本服务投递(mall.pms.sync, direct) ──product.sync──► [mall.pms.es-sync] ──► mall-search 的消费者
 * </pre>
 */
public final class MqTopology {

    private MqTopology() {
    }

    // ==================== 商品索引同步(mall.pms.sync) ====================

    /** 商品索引同步交换机（工作队列、重试队列、死信队列都挂在它上面） */
    public static final String SYNC_EXCHANGE = "mall.pms.sync";
    /** 同步工作队列：消费端（mall-search）调用 ES 写/删文档。⚠️ 本服务不声明它，见下 */
    public static final String SYNC_QUEUE = "mall.pms.es-sync";
    /** 正常投递用的路由键 */
    public static final String SYNC_ROUTING_KEY = "product.sync";
    /** 重试队列路由键（消费方使用；列在这里是为了"契约在一处可见"） */
    public static final String SYNC_RETRY_ROUTING_KEY = "product.sync.retry";
    /** 死信队列路由键（消费方使用） */
    public static final String SYNC_DLQ_ROUTING_KEY = "product.sync.dlq";
}
