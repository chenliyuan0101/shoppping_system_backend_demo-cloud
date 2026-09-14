package com.mall.search.degraded;

import com.jayway.jsonpath.JsonPath;
import com.mall.search.client.ProductIndexDocClient;
import com.mall.search.dto.IndexDocsResult;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.support.SearchTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>失败语义套件（其一）：ES 不可达</b> —— 规格 §6 的前两行。
 *
 * <h2>这个套件在验什么</h2>
 * 把 ES 指向**死端口**（{@code https://127.0.0.1:9}，本机不会有东西监听），然后逐个打内部接口，
 * 验"ES 挂了的时候"本服务的**承诺**：
 * <ol>
 *   <li>{@code status} 如实报 {@code docCount=-1}（**不是 0**：0 的含义是"索引存在但没有文档"，
 *       两者混起来运维就分不清"ES 挂了"和"索引是空的"）；</li>
 *   <li>{@code search} 明确报业务错误码，而不是"返回半截/空结果假装成功"；</li>
 *   <li>{@code reindex} **明确报错且不声称成功**（不允许写出一份半截索引却说"重建完成"）；</li>
 *   <li>{@code sync} 返回 {@code false}（失败要能被调用方看见），**不抛异常**（索引同步失败不该阻塞商品写操作）；</li>
 *   <li>{@code delete} 保持幂等口径（只记 warn，不报错）；</li>
 *   <li>{@code sync-by-brand} 报 {@code 0} 条，不把"取到的条目数"当成"同步成功数"。</li>
 * </ol>
 *
 * <h2>为什么这个类**不继承** SearchTestBase</h2>
 * {@code SearchTestBase} 带"ES 必须可达，否则红"的守卫（正常环境的基类），
 * 而本套件的**前提就是 ES 不可达** —— 所以直接继承共享支撑 {@link SearchTestSupport}
 * （它只有常量与 HTTP/ES 辅助，没有环境守卫）。
 *
 * <h2>这里**没有**验证的部分（不许含糊）</h2>
 * <ul>
 *   <li>ES 挂了以后**检索降级回 MySQL LIKE** 这件事**不在本服务**：本服务没有库，
 *       规格 §5 第 5 条把这条降级划给 product（P6-3 落地）。所以本套件只验"如实报错"，
 *       不验"用户还能搜到东西"—— 后者若被写成绿色，就是**假绿**；</li>
 *   <li>本套件里 product 侧是**桩**（{@code page} 返回一篇文档），因此它验的是"取数成功、写 ES 失败"
 *       这条路径；"取数阶段就失败"由打桩层的
 *       {@code ProductIndexContentStubbedEsTest#reindex_contentSourceFails_...} 覆盖
 *       （那条断言的是**索引一篇不动**）。两条合起来才是 §6 要求的"不得写半截索引"。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // 故意指向死端口：端口 9（discard）本机不监听 ⇒ 连接立即被拒，不会挂住用例
        "spring.elasticsearch.uris=https://127.0.0.1:9",
        // 测试期关 MQ（与 src/test/resources/application.properties 同口径，这里写明白）
        "mall.mq.enabled=false"
})
@AutoConfigureMockMvc
class SearchDegradedEsTest extends SearchTestSupport {

    private static final long FAKE_SPU_ID = FAKE_SPU_ID_BASE + 200;

    /**
     * product 侧仍然打桩：本套件要验的是"ES 不可达"这一件事，
     * 不能让"product 是不是正好在跑"混进来（否则同一份源码的成败会跟着环境漂移）。
     */
    @MockitoBean
    private ProductIndexDocClient productIndexDocClient;

    @Test
    @DisplayName("[失败语义/ES 不可达] status 如实报 docCount=-1（不是 0，也不是抛栈），且不误报 Redis 故障")
    void status_esDown_reportsDocCountMinusOne() throws Exception {
        MvcResult r = mockMvc.perform(get("/internal/v1/search/status").header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(0, codeOf(b), "自检接口本身必须能用（ES 挂了也要能回答'我坏了'）");
        assertEquals(INDEX, JsonPath.read(b, "$.data.index"), "索引名恒为 mall_product");
        assertEquals(-1L, ((Number) JsonPath.read(b, "$.data.docCount")).longValue(),
                "⚠️ ES 不可达必须报 -1；报 0 会把'ES 挂了'伪装成'索引是空的'");
        assertTrue(((Number) JsonPath.read(b, "$.data.pendingCount")).longValue() >= 0,
                "本用例只降级 ES（Redis 正常），pendingCount 应当 >= 0；" +
                        "-1 说明 Redis 也连不上，那本用例的前提就不成立");
        assertFalse(((String) JsonPath.read(b, "$.data.titleAnalyzer")).isBlank(),
                "分词器探测不可用时也要给出明确值（现实现回落 standard），不能是空串");
        // P6-5 #6：ES 集群信息在**不可用**分支上必须是"四个 null + numberOfNodes=-1"
        // （与单体 EsPingVO 的可用/不可用两条分支同形；0 会被读成"集群里一个节点都没有"）
        assertNull(JsonPath.read(b, "$.data.clusterName"), "ES 不可达 ⇒ clusterName=null");
        assertNull(JsonPath.read(b, "$.data.nodeName"), "ES 不可达 ⇒ nodeName=null");
        assertNull(JsonPath.read(b, "$.data.esVersion"), "ES 不可达 ⇒ esVersion=null");
        assertNull(JsonPath.read(b, "$.data.healthStatus"), "ES 不可达 ⇒ healthStatus=null");
        assertEquals(-1, ((Number) JsonPath.read(b, "$.data.numberOfNodes")).intValue(),
                "ES 不可达 ⇒ numberOfNodes=-1（**不是 0**：0 的含义是'集群里没有节点'，那是另一件事）");
    }

    @Test
    @DisplayName("[失败语义/ES 不可达] product/{id} 明确报错，**绝不**把'读不到'伪装成'索引里没有这篇'")
    void productById_esDown_reportsErrorNotAbsent() throws Exception {
        MvcResult r = mockMvc.perform(get("/internal/v1/search/product/" + FAKE_SPU_ID)
                        .header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(500, codeOf(b),
                "ES 读不到必须报业务错误码：返回 code=0 + data:null 会让调用方断言"
                        + "'这个商品没进索引'（真相是根本读不到）—— 这类'不可用伪装成没有数据'是本项目反复禁止的");
        assertNull(JsonPath.read(b, "$.data"), "失败响应不得携带 data");
        assertEquals("系统繁忙，请稍后重试", messageOf(b), "对外文案与现状一致（不泄漏中间件细节）");
    }

    @Test
    @DisplayName("[失败语义/ES 不可达] search 明确报业务错误码，不返回半截/空结果假装成功")
    void search_esDown_returnsExplicitErrorNotPartialResult() throws Exception {
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"keyword\":\"耳机\",\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(500, codeOf(b),
                "检索失败必须报业务错误码 500（统一响应体里），不能是 code=0+空列表（那是'搜不到'的假象）");
        assertNull(JsonPath.read(b, "$.data"), "失败响应不得携带 data（否则调用方可能把它当成有效结果）");
        assertEquals("系统繁忙，请稍后重试", messageOf(b), "对外文案与现状一致（不泄漏中间件细节）");
    }

    @Test
    @DisplayName("[失败语义/ES 不可达] reindex 明确报错、data 为空 —— 绝不声称'重建成功'")
    void reindex_esDown_failsLoudlyAndClaimsNothing() throws Exception {
        // 取数阶段**成功**（桩给一篇文档），失败发生在写 ES 这一步 —— 也就是"重建到一半写不进去"
        when(productIndexDocClient.page(anyLong(), anyLong()))
                .thenReturn(new IndexDocsResult(1L, List.of(stubDoc(FAKE_SPU_ID, "降级用例·ES 写不进去"))));

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/reindex", "{}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(500, codeOf(b), "重建失败必须是显式错误码（不许静默成功）");
        assertTrue(((String) messageOf(b)).startsWith("商品索引重建失败"),
                "错误文案必须点明'重建失败'，实际=" + messageOf(b));
        assertNull(JsonPath.read(b, "$.data"),
                "⚠️ 失败时**不得**返回 ReindexResult（哪怕 indexed=0）—— 那会让人以为'重建跑过了、只是没商品'");
    }

    @Test
    @DisplayName("[失败语义/ES 不可达] 单条同步返回 false（失败可见），且不抛异常（不阻塞商品写操作）")
    void sync_esDown_returnsFalseWithoutThrowing() throws Exception {
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1L, List.of(stubDoc(FAKE_SPU_ID, "降级用例·单条同步"))));

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/sync/" + FAKE_SPU_ID, "{}"))
                .andExpect(status().isOk()).andReturn();

        assertEquals(0, codeOf(body(r)), "单条同步失败**不是**接口错误（现状语义：靠重试/全量重建兜底）");
        assertEquals(Boolean.FALSE, JsonPath.read(body(r), "$.data"),
                "必须返回 false —— 失败要能被调用方看见，不能假装成功");
    }

    @Test
    @DisplayName("[失败语义/ES 不可达] 删除保持幂等：只记 warn，不报错（重试不会因为'删不掉'而恶化）")
    void delete_esDown_remainsIdempotent() throws Exception {
        MvcResult r = mockMvc.perform(delete("/internal/v1/search/product/" + FAKE_SPU_ID)
                        .header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();

        assertEquals(0, codeOf(body(r)), "删除是幂等动作：ES 不可达时只记 warn（与现状一致）");
    }

    @Test
    @DisplayName("[失败语义/ES 不可达] 按品牌同步报 0 条 —— 不把'取到的条目数'当成'同步成功数'")
    void syncByBrand_esDown_reportsZeroSynced() throws Exception {
        when(productIndexDocClient.byBrand(anyLong()))
                .thenReturn(new IndexDocsResult(1L, List.of(stubDoc(FAKE_SPU_ID, "降级用例·按品牌"))));

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/sync-by-brand/937", "{}"))
                .andExpect(status().isOk()).andReturn();

        assertEquals(0, codeOf(body(r)));
        assertEquals(0, ((Number) JsonPath.read(body(r), "$.data")).intValue(),
                "一篇都没写进 ES ⇒ 必须是 0；返回 1 就是'把取到的条目当成写成功的'，会让重试逻辑失效");
    }

    // ---------- helpers ----------

    /** 只填本用例需要的字段（索引文档 POJO，字段齐全的版本见打桩层的 fullDoc） */
    private ProductSearchDoc stubDoc(long spuId, String title) {
        ProductSearchDoc doc = new ProductSearchDoc();
        doc.setSpuId(spuId);
        doc.setTitle(title);
        doc.setStatus(1);
        doc.setMinPrice(1000L);
        doc.setTotalStock(1);
        doc.setSales(0);
        return doc;
    }
}
