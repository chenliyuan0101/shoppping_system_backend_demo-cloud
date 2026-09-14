package com.mall.product.dto;

import lombok.Data;

/**
 * 按 SPU 聚合的在架 SKU 统计(最低价/总库存)，由 {@code SkuMapper.selectShelfAggregates()} 填充。
 */
@Data
public class SkuAggregate {

    private Long spuId;
    /** 在架 SKU 最低价(分) */
    private Long minPrice;
    /** 在架 SKU 总库存 */
    private Long totalStock;
}
