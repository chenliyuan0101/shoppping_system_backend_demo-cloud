package com.mall.product.dto;

import java.util.List;

/**
 * ES 检索命中的 spuId 列表(已按请求的排序规则排好) + 命中总数。
 *
 * <p>设计：ES 只负责"找出哪些商品 + 排序 + 总数"，展示字段仍由 MySQL 侧
 * {@code ProductListAssembler} 装配，避免索引里维护两份展示口径。
 */
public record ProductIdPage(List<Long> spuIds, long total) {
}
