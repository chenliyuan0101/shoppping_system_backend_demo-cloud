package com.mall.search.dto;

/**
 * 检索服务的自检快照（{@code GET /internal/v1/search/status} 的 {@code data}）。
 *
 * <p>⚠️ 取值语义与现状**逐字一致**（规格 §5 第 4 条）：
 * <ul>
 *   <li>{@code docCount}：索引文档数；**索引不存在/ES 不可达 → -1**（不是 0！
 *       0 的含义是"索引存在但没有文档"，两者必须能区分）；</li>
 *   <li>{@code pendingCount}：待同步队列长度；**Redis 不可用 → -1**；</li>
 *   <li>{@code titleAnalyzer}：当前生效的标题分词器（探测值 smartcn/cjk/standard）。</li>
 * </ul>
 *
 * <h2>P6-5 #6（D4）：新增 5 个 ES 集群字段 —— **只加字段，不改既有字段**</h2>
 * 单体 {@code AdminEsController#ping()} 要把集群信息改成**转发**到本端点（转发后单体
 * 就彻底不再有 ES 客户端，P6-6 才删得干净），所以集群信息必须在这里出现。
 * <pre>
 * 字段              来源（与单体 ping() 逐字同一个取法）                         ES 不可达时
 * clusterName      info().clusterName()                                        null
 * nodeName         info().name()                                               null
 * esVersion        info().version().number()                                   null
 * healthStatus     cluster().health().status().jsonValue()  green/yellow/red    null
 * numberOfNodes    cluster().health().numberOfNodes()                          -1
 * </pre>
 * ⚠️ <b>字段名是契约</b>（单体那边按这些名字映射进 {@code EsPingVO}，见 P6-5 规格 §二 D4）：
 * 不许改名、不许改类型、不许改成"不可用时省略该键"（{@code null} 是明确的取值，省略会让
 * 按路径取值的一方取到"路径不存在"而不是 null）。既有 4 个字段的名称/类型/语义**一个字都没动**。
 *
 * @param index         索引名（**恒为 {@code mall_product}**，不改名、不加别名）
 * @param docCount      {@code count()} 的结果（-1 = 不可用）
 * @param pendingCount  {@code pendingCount()} 的结果（-1 = Redis 不可用）
 * @param titleAnalyzer {@code titleAnalyzer()} 的结果
 * @param clusterName   ES 集群名（P6-5 新增；不可用 → null）
 * @param nodeName      ES 节点名（P6-5 新增；不可用 → null）
 * @param esVersion     ES 版本号（P6-5 新增；不可用 → null）
 * @param healthStatus  ES 健康状态 green/yellow/red（P6-5 新增；不可用 → null）
 * @param numberOfNodes ES 节点数（P6-5 新增；不可用 → **-1**）
 */
public record SearchStatusVO(String index, long docCount, long pendingCount, String titleAnalyzer,
                             String clusterName, String nodeName, String esVersion,
                             String healthStatus, Integer numberOfNodes) {
}
