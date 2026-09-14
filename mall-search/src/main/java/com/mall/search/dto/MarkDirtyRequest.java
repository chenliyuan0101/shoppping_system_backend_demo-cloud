package com.mall.search.dto;

import java.util.List;

/**
 * {@code POST /internal/v1/search/mark-dirty} 的请求体（P6-5 #5）。
 *
 * <p>形状照现状的 {@code markDirty(Collection<Long> spuIds)}：只有一个字段。
 *
 * <p>⚠️ 字段缺失 / null / 空列表一律按"没有要标记的"处理（{@code added:0}，**不是**错误）：
 * 调用方（订单/售后链路）在批量里出现空集合是正常情况，把它变成 400 会让调用方为了兼容
 * 而写一堆无意义的判空。
 *
 * @param spuIds 需要标记为"待同步到索引"的 spuId（可空、可含 null —— 实现里会过滤）
 */
public record MarkDirtyRequest(List<Long> spuIds) {
}
