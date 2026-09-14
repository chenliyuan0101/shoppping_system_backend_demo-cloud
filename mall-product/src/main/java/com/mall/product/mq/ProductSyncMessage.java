package com.mall.product.mq;

import java.util.Collection;
import java.util.List;

/**
 * 商品索引同步消息（JSON 序列化）。**字段名是与 {@code mall-search} 消费者的契约，一个字不许改**。
 *
 * <p>两种语义共用一个队列（与单体时代的 {@code com.mall.demo.pms.mq.ProductSyncMessage} 逐字同形）：
 * <ul>
 *   <li>{@code brandId == null}：按 {@code spuIds} 逐个同步（新增/改价/上下架/删除、订单链路库存销量变化）</li>
 *   <li>{@code brandId != null}：同步该品牌下全部在架商品（品牌改名/删除）</li>
 * </ul>
 *
 * <p>⚠️ 消费方（{@code mall-search} 的 {@code ProductSyncConsumer}）用 Jackson 反序列化到它自己那份同名 record
 * ⇒ **两边靠 JSON 字段名对齐**（{@code spuIds} / {@code brandId} / {@code enqueuedAtMillis}）。
 * 这里不共享类：跨服务的"共享 DTO"会把两个服务的编译期绑死，与各服务自持契约副本的既定口径冲突
 * （同一理由见 {@code MqTopology} 的类注释）。
 *
 * @param spuIds           待同步商品 id（品牌语义下为空）
 * @param brandId          品牌 id（商品语义下为 null）
 * @param enqueuedAtMillis 入队时间，仅用于日志排查
 */
public record ProductSyncMessage(List<Long> spuIds, Long brandId, long enqueuedAtMillis) {

    /** 单批最多带多少个 spuId，避免一次消息过大（与消费方同名常量同值） */
    public static final int MAX_BATCH = 100;

    public static ProductSyncMessage ofSpuIds(Collection<Long> ids) {
        return new ProductSyncMessage(List.copyOf(ids), null, System.currentTimeMillis());
    }

    public static ProductSyncMessage ofBrand(long brandId) {
        return new ProductSyncMessage(List.of(), brandId, System.currentTimeMillis());
    }

    public boolean isBrand() {
        return brandId != null;
    }
}
