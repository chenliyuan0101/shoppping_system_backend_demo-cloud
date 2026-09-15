package com.mall.trade.common.dto;

/**
 * 检索域自检快照（{@code GET /internal/v1/search/status} 的响应体）。
 *
 * <p>为什么在单体侧再定义一份：跨服务契约以 <b>JSON 字段名</b> 为准。检索域自己那份 record 属于
 * {@code mall-search} 的内部类型，单体直接依赖它会把两个服务的编译期绑死
 * （与 {@code ProductSyncMessage} 不在服务间共享同一条口径）。
 *
 * <p>字段与 {@code mall-search} 的 {@code SearchStatusVO} <b>逐字对应</b>：
 * <ul>
 *   <li>{@code index/docCount/pendingCount/titleAnalyzer}：P6-2 就有的四个观测值
 *       （{@code docCount=-1}=索引不存在或 ES 不可用，{@code pendingCount=-1}=Redis 不可用）；</li>
 *   <li>{@code clusterName/nodeName/esVersion/healthStatus/numberOfNodes}：<b>P6-5 #6 新增</b>，
 *       给单体 {@code GET /api/admin/es/ping} 转发用（此前那五个值来自单体自己的 ES 客户端）。
 *       ES 不可达时它们是 {@code null/null/null/null/-1} —— 与 {@code EsPingVO} 的"可用/不可用"
 *       两条分支形状一致。</li>
 * </ul>
 */
public record SearchStatusVO(String index,
                             long docCount,
                             long pendingCount,
                             String titleAnalyzer,
                             String clusterName,
                             String nodeName,
                             String esVersion,
                             String healthStatus,
                             Integer numberOfNodes) {
}
