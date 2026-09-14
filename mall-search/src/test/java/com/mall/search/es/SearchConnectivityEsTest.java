package com.mall.search.es;

import com.jayway.jsonpath.JsonPath;
import com.mall.search.support.SearchTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>连通性套件</b>：证明"本服务真的能连上本机 ES"，并且自检接口把这些事实报出来。
 *
 * <h2>从哪来 / 改了什么</h2>
 * 迁移自单体 {@code support/ElasticsearchConnectivityTest}（一个 spike 型用例）。原版验的是
 * https + 账号密码 + 自签证书（SSL bundle）能不能连上 ES，并打管理端 {@code /api/admin/es/ping}。
 * 到这里有两处**必须改**，理由写清楚（不改就是照搬环境常量）：
 * <ol>
 *   <li>{@code $.data.esVersion == "9.5.3"} / {@code numberOfNodes == 1} / {@code status == "green"}：
 *       这些是<b>本机环境常量</b>，不是契约 —— 换台机器、加个节点、单节点副本数一变就红，
 *       而且红得毫无信息量。本类只保留"连通性事实"：版本是 9.x（客户端与目标大版本对齐）、
 *       集群名非空、健康状态可读、节点数 >= 1；</li>
 *   <li>mall-search <b>没有</b> {@code /api/admin/es/ping}（它只有 6 个内部端点 + 无 admin 面），
 *       等价物是 {@code GET /internal/v1/search/status}（{@code SearchStatusVO}）。原版那段
 *       "轮询等 green 最多 15s"的存在理由是"别的进程可能正在 reindex，集群短暂 YELLOW"——
 *       本类保留同样的**容忍**（有界重试），但把它标注为"跨进程不确定性的补丁"，而不是被测契约。</li>
 * </ol>
 *
 * <p>与 {@code InternalSearchApiEsTest} 的分工：那边验**接口契约**（鉴权闸门/映射/分页/排序），
 * 这边只验**连通性与自检**（能连、能读集群信息、自检值与实测一致、分词器真的可用）。
 */
class SearchConnectivityEsTest extends SearchTestBase {

    @Test
    @DisplayName("[连通性] 能连上本机 Elasticsearch：版本 9.x / 集群名非空 / 健康状态可读 / 节点数 >= 1")
    void connectAndReadClusterInfo() throws Exception {
        var info = elasticsearchClient.info();
        String version = info.version().number();
        String cluster = info.clusterName();
        var health = elasticsearchClient.cluster().health();

        System.out.println("[ES] cluster=" + cluster + " version=" + version
                + " nodes=" + health.numberOfNodes() + " status=" + health.status());

        assertNotNull(version, "版本号必须能读到（连不上时这里会抛异常 ⇒ 用例红，而不是静默跳过）");
        assertTrue(version.startsWith("9."),
                "本项目锁定 ES 9.x（客户端库与目标大版本对齐），实际=" + version
                        + " —— 这里**刻意不断言补丁版本**（9.5.3 是本机环境常量，不是契约）");
        assertFalse(cluster == null || cluster.isBlank(), "集群名必须非空");
        assertNotNull(health.status(), "集群健康状态必须可读");
        assertTrue(health.numberOfNodes() >= 1, "至少要有 1 个节点可服务");
    }

    @Test
    @DisplayName("[连通性/自检] status 的 docCount 与 ES 实测一致，分词器已探测出 smartcn")
    void statusReflectsRealEsState() throws Exception {
        long actual = docCount();
        assertTrue(actual > 0, "索引里应当有真实文档（本用例只验连通性，不自己造语料）");

        MvcResult r = mockMvc.perform(get("/internal/v1/search/status").header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(0, codeOf(b), "自检接口必须可用");
        assertEquals(INDEX, JsonPath.read(b, "$.data.index"), "索引名恒为 mall_product（不改名、不加别名）");
        assertEquals(actual, ((Number) JsonPath.read(b, "$.data.docCount")).longValue(),
                "自检里的文档数必须等于 ES 实测（对不上说明自检在报缓存/假值）");
        assertEquals("smartcn", JsonPath.read(b, "$.data.titleAnalyzer"),
                "标题分词器必须是 smartcn（官方中文分词插件；探测回落说明插件没装或没生效，"
                        + "那会让 retrieved 结果与单体不一致）");
        assertTrue(((Number) JsonPath.read(b, "$.data.pendingCount")).longValue() >= 0,
                "Redis 正常时待同步数应当 >= 0（-1 表示 Redis 不可用，属另一个套件的场景）");
    }

    @Test
    @DisplayName("[连通性/分词器] smartcn 真的能分词（不是配置里写了 smartcn 而已）")
    void smartcnAnalyzerIsActuallyUsable() throws Exception {
        var response = elasticsearchClient.indices().analyze(a -> a
                .analyzer("smartcn").text("蓝牙耳机降噪"));
        List<String> tokens = response.tokens().stream().map(t -> t.token()).toList();

        System.out.println("[ES 分词] smartcn(\"蓝牙耳机降噪\") = " + tokens);
        assertFalse(tokens.isEmpty(), "smartcn 必须能切出词元");
        assertTrue(tokens.contains("耳机"),
                "中文分词器应把'耳机'切成独立词元（当前结果=" + tokens + "）；"
                        + "切不出来说明生效的其实是 standard（逐字），检索口径会与单体漂移");
    }
}
