package com.mall.search.dto;

/**
 * ES 集群信息快照（P6-5 #6 的 search 半边）。
 *
 * <p><b>为什么需要它</b>：单体的 {@code GET /api/admin/es/ping} 今天用**本地** ES 客户端取这五项
 * （集群名 / 节点名 / 版本 / 健康状态 / 节点数）。P6-5 要把 {@code ping()} 改成**转发**到 search 的
 * {@code /internal/v1/search/status}，好让单体**彻底不再有 ES 客户端**（P6-6 才能干净地删
 * {@code com.mall.demo.pms} 整包与 ES 依赖）⇒ 这五项必须由 search 提供。
 *
 * <p><b>取值口径与单体逐字对齐</b>（{@code AdminEsController#ping}）：
 * <pre>
 * clusterName    elasticsearchClient.info().clusterName()
 * nodeName       elasticsearchClient.info().name()          ← 我们连上的那个节点的名字
 * esVersion      elasticsearchClient.info().version().number()
 * healthStatus   elasticsearchClient.cluster().health().status().jsonValue()   ← green/yellow/red
 * numberOfNodes  elasticsearchClient.cluster().health().numberOfNodes()
 * </pre>
 *
 * <p>⚠️ <b>"不可用"是独立的一支</b>（与 {@code EsPingVO} 的可用/不可用两条分支一致）：
 * ES 读不到时四个字符串字段为 {@code null}、{@code numberOfNodes = -1}（**不是 0** —— 0 会被读成
 * "集群里一个节点都没有"，那是另一回事）。调用方据此把 {@code available=false} 报出去。
 *
 * @param clusterName   集群名（不可用 → null）
 * @param nodeName      节点名（不可用 → null）
 * @param esVersion     ES 版本号（不可用 → null）
 * @param healthStatus  健康状态 green / yellow / red（不可用 → null）
 * @param numberOfNodes 节点数（不可用 → **-1**）
 */
public record EsClusterInfo(String clusterName, String nodeName, String esVersion,
                            String healthStatus, Integer numberOfNodes) {

    /** ES 不可达/读取失败时的取值（四个 null + numberOfNodes=-1，与 {@code EsPingVO} 的失败分支同形） */
    public static EsClusterInfo unavailable() {
        return new EsClusterInfo(null, null, null, null, -1);
    }
}
