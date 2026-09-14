package com.mall.search.dto;

/**
 * {@code POST /internal/v1/search/mark-dirty} 的响应 {@code data}（P6-5 #5）。
 *
 * <p>只有 {@code added} 一个字段：**这次调用真正新加进** Redis 待同步集合
 * （{@code mall:es:pending}）的成员数 —— 也就是 Redis {@code SADD} 的返回值语义
 * （已经在集合里的 id 不重复计数）。
 *
 * <p>三种取值要能区分（否则调用方分不清"标记成功"和"标记没地方放"）：
 * <ul>
 *   <li>{@code >= 0}：标记成功，数字是新增条数（**0 = 全都在集合里了，不是失败**）；</li>
 *   <li>{@code 0}：入参为空/null 时**不调用 Redis**，直接 0；</li>
 *   <li>{@code -1}：**Redis 不可用**（兜底通道断了，与 {@code pendingCount()} / {@code docCount}
 *       的 "-1 = 不可用" 同一口径）。此时这些商品只能靠定时轮询之外的手段兜底（MQ 或全量重建）。</li>
 * </ul>
 *
 * @param added 本次新增到待同步集合的条数（-1 = Redis 不可用）
 */
public record MarkDirtyResult(long added) {
}
