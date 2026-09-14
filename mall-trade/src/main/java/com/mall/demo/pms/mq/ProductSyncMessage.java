package com.mall.demo.pms.mq;

import java.util.Collection;
import java.util.List;

/**
 * 商品索引同步消息(JSON 序列化)。
 *
 * <p>两种语义共用一个队列：
 * <ul>
 *   <li>{@code brandId == null}：按 {@code spuIds} 逐个同步(新增/改价/上下架/删除、订单链路库存销量变化)</li>
 *   <li>{@code brandId != null}：同步该品牌下全部在架商品(品牌改名/删除)</li>
 * </ul>
 *
 * @param spuIds           待同步商品 id(品牌语义下为空)
 * @param brandId          品牌 id(商品语义下为 null)
 * @param enqueuedAtMillis 入队时间，仅用于日志排查
 */
public record ProductSyncMessage(List<Long> spuIds, Long brandId, long enqueuedAtMillis) {

    /** 单批最多带多少个 spuId，避免一次消息过大 */
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
