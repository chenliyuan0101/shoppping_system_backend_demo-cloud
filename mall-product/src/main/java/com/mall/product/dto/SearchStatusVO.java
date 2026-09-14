package com.mall.product.dto;

/**
 * 检索服务自检快照（{@code GET /internal/v1/search/status} 的 {@code data}）。
 *
 * <p>与 mall-search 的 {@code SearchStatusVO} **字段逐字一致**（契约副本：本项目不共享 jar）。
 * 语义要记住（否则会把"不可用"读成"没有数据"）：
 * <ul>
 *   <li>{@code docCount}：-1 = 索引不存在/ES 不可达（**不是 0**）；</li>
 *   <li>{@code pendingCount}：-1 = 待同步链路不可用（**不是 0**）；</li>
 *   <li>{@code titleAnalyzer}：探测链全部失败时是 {@code "standard"}（兜底值，不等于"分词器可用"）。</li>
 * </ul>
 */
public record SearchStatusVO(String index, long docCount, long pendingCount, String titleAnalyzer) {
}
