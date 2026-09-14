package com.mall.search.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.mall.search.dto.ProductSearchDoc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 检索服务用例的**共享支撑**（常量 + 辅助方法 + ES 可达性守卫）。
 *
 * <p>刻意**不带任何 Spring 测试注解** —— 这样它的子类可以各自决定"测试上下文长什么样"：
 * <ul>
 *   <li>{@link SearchTestBase}：**hermetic 默认**（出站依赖被 {@code @MockitoBean} 替换成桩）；</li>
 *   <li>{@code com.mall.search.integration.SearchReindexIntegrationTest}：**显式退出 hermetic**
 *       （它就是要真调 mall-product，所以它**不继承** SearchTestBase，而是继承本类）。</li>
 * </ul>
 * "默认安全、要联网必须显式声明"是这个分层的核心：谁想让用例碰网络，必须自己写清楚。
 *
 * <p>⚠️ ES 不可达时用例应当**失败**而不是跳过（本项目有 {@code Skipped: 24 + BUILD SUCCESS} 的假绿先例），
 * 所以这里的 ES 守卫用断言；只有**显式集成层**才允许用 {@code assume} 跳过（且必须在报告里交代）。
 */
public abstract class SearchTestSupport {

    protected static final String TOKEN_HEADER = "X-Internal-Token";

    /** 索引名：**恒为 mall_product**（不改名、不加别名） */
    protected static final String INDEX = "mall_product";

    /** 假 spuId 号段（9 开头）：真实数据里不存在，供打桩层造数据用，用完即删 */
    protected static final long FAKE_SPU_ID_BASE = 9_100_000_000L;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ElasticsearchClient elasticsearchClient;

    /** **运行时配置里的**内部令牌（不是测试专用值）：带上它 == 带上这个环境自己配的那把 */
    @Value("${mall.internal.token:}")
    protected String internalToken;

    /** **运行时配置里的**商品域地址；集成层的探针与出站客户端共用它（单一真相来源） */
    @Value("${mall.product.base-url:}")
    protected String productBaseUrl;

    protected final String suffix = String.valueOf(System.nanoTime() % 1_000_000L);

    // ⚠️ "ES 必须可达"这条守卫放在 {@link SearchTestBase} 里而**不是这里**：
    //    本类是"共享支撑"，还包括**故意把 ES 指向坏端口**的降级用例（失败语义套件）——
    //    那些用例当然不该被"ES 必须可达"拦住。谁要这条守卫，就继承 SearchTestBase。

    // ==================================================================
    // HTTP 辅助（内部接口）
    // ==================================================================

    /** 带**运行时配置的那把**内部令牌的 JSON POST */
    protected MockHttpServletRequestBuilder internalPost(String path, String json) {
        return post(path).header(TOKEN_HEADER, internalToken)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** **不带**内部令牌的 JSON POST（"无令牌 → 403"专用） */
    protected MockHttpServletRequestBuilder anonymousPost(String path, String json) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /**
     * 统一响应体里的业务码（**HTTP 恒为 200**，成败只看 body 里的 {@code code}）。
     *
     * <p>⚠️ 必须先判 HTTP 状态：本项目有过"把 {@code Connection refused} 伪装成
     * {@code expected 0 but was 500}"的先例 —— 断言里一定要用本方法读 **body 的 code**，
     * 并在失败信息里带上原始 body，别让"连不上"看起来像"业务码不对"。
     */
    protected static int codeOf(String responseBody) {
        try {
            return ((Number) com.jayway.jsonpath.JsonPath.read(responseBody, "$.code")).intValue();
        } catch (Exception e) {
            throw new AssertionError("响应体不是统一响应格式（读不到 $.code）：" + responseBody, e);
        }
    }

    /** 统一响应体里的 message（失败文案断言用） */
    protected static String messageOf(String responseBody) {
        try {
            Object m = com.jayway.jsonpath.JsonPath.read(responseBody, "$.message");
            return m == null ? null : String.valueOf(m);
        } catch (Exception e) {
            throw new AssertionError("响应体不是统一响应格式（读不到 $.message）：" + responseBody, e);
        }
    }

    // ==================================================================
    // ES 辅助
    // ==================================================================

    /** 索引里的文档数（-1 = 索引不存在/ES 读不到） */
    protected long docCount() {
        try {
            return elasticsearchClient.count(c -> c.index(INDEX)).count();
        } catch (Exception e) {
            return -1L;
        }
    }

    /** 文档是否存在（按 id 的 GET 是**实时**的，不需要 refresh） */
    protected boolean docExists(long spuId) {
        return getDoc(spuId) != null;
    }

    /** 按 id 取文档（不存在返回 null） */
    protected ProductSearchDoc getDoc(long spuId) {
        try {
            GetResponse<ProductSearchDoc> r = elasticsearchClient.get(
                    g -> g.index(INDEX).id(String.valueOf(spuId)), ProductSearchDoc.class);
            return r.found() ? r.source() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删掉一篇文档（**只用于清理本用例自己造的假数据**），失败只告警。
     *
     * <p>⚠️ 必须带 {@code refresh(Refresh.True)}：ES 的 {@code _count} **不是实时的**，
     * 删除不刷新的话紧接着的 {@code docCount()} 仍会看到旧计数 —— 我用例里
     * "清理后文档数回到基线"这条断言第一次跑就是这么红的（实测踩到，记在这里）。
     */
    protected void deleteDocQuietly(long spuId) {
        try {
            elasticsearchClient.delete(d -> d.index(INDEX).id(String.valueOf(spuId))
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
        } catch (Exception ignored) {
            // 清理动作不该影响结论；残留会被"假号段没有文档"的断言抓出来
        }
    }

    /**
     * 假号段（9 开头）里的文档数 —— 用来断言"测试没给共享索引留垃圾"。
     *
     * <p>⚠️ 为什么用"假号段计数"而不是"全局文档数回到基线"：本索引是**共享**的
     * （单体在检索、活着的 mall-search 在跑定时任务、主 agent 还会做全量重建），
     * 一次跨阶段的全局计数比较会被任何第三方写入/重建扰动 —— 我实测踩到过：
     * 05:45 有人正在 reindex（删索引→建索引→写 1373 篇），我的用例当场看到
     * {@code no_shard_available_action_exception} 和 {@code docCount=-1}，报出来的却是
     * "测试留了垃圾"。**假号段计数**只统计我自己的文档，别人怎么写都不会让它变红。
     */
    protected long fakeSegmentDocCount() {
        try {
            return elasticsearchClient.count(c -> c.index(INDEX)
                    .query(q -> q.range(r -> r.number(n -> n.field("spuId")
                            .gte((double) FAKE_SPU_ID_BASE))))).count();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 取一篇**真实**文档的 spuId（不在假号段内的），用来断言"我没误删/误改别人的文档"。
     *
     * <p>比"文档总数没变"更锐利、也更抗干扰：总数会被第三方写入/重建扰动，而"这一篇还在"
     * 只取决于本用例有没有动它。取不到（索引为空）时返回 null，由调用方决定怎么报。
     */
    protected Long anyRealSpuId() {
        try {
            var response = elasticsearchClient.search(s -> s.index(INDEX).size(1)
                            .query(q -> q.range(r -> r.number(n -> n.field("spuId")
                                    .lt((double) FAKE_SPU_ID_BASE))))
                            .sort(so -> so.field(f -> f.field("spuId")
                                    .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc))),
                    ProductSearchDoc.class);
            return response.hits().hits().isEmpty() ? null : response.hits().hits().get(0).source().getSpuId();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================
    // 跨服务可达性探针（只给**显式集成**层用）
    // ==================================================================

    /**
     * mall-product 是否可达（**用与出站客户端同一个配置值**，短超时）。
     *
     * <p>⚠️ 只允许"显式集成层"用它做 {@code assumeTrue} 守卫：跳过意味着**这条没验**，
     * 必须在报告里列出来并说明原因（不能拿它当"通过"）。
     * <p>⚠️ 读的是**同一个** {@code mall.product.base-url}（而不是另取一个 System 属性）：
     * 否则"探针说可达"与"客户端实际打哪个地址"可能不一致 —— 那正是"探针自己骗人"那一类。
     */
    protected boolean productReachable() {
        if (productBaseUrl == null || productBaseUrl.isBlank()) {
            return false;
        }
        // lb:// 形式（默认生产接线）需要服务发现，探针不做发现——那种场景由调用方给直连地址
        if (productBaseUrl.startsWith("lb://")) {
            return false;
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(productBaseUrl + "/actuator/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
