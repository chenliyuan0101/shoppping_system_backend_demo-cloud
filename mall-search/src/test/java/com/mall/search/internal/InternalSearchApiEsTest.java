package com.mall.search.internal;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import com.jayway.jsonpath.JsonPath;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.support.SearchTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>ES 单层</b>用例：只依赖真 ES，**完全不碰 mall-product**。
 *
 * <p>覆盖：内部接口的鉴权闸门（双向）、自检快照、检索的筛选/排序/分页/夹取、索引的 mapping 逐字复刻、
 * 以及"不留垃圾文档"。
 * <p>⚠️ 边界（主 agent 2026-09-14 的返工要求）：**依赖出站内容的路径不在这里**——
 * `sync/sync-by-brand` 走的是 `ProductIndexDocClient`，那部分在
 * {@code ProductIndexContentStubbedEsTest}（打桩）与 {@code SearchReindexIntegrationTest}（显式集成）里。
 * 这样本套件在 product 起没起、令牌配成什么的情况下都能跑，不会红/绿漂移。
 * <p>⚠️ 真实 id 一律**从索引里读**（不写死 1001）：写死会让用例随"某个商品被下架/删除"而假红。
 */
class InternalSearchApiEsTest extends SearchTestBase {

    /** 不存在的 spuId（用于"删除不存在的文档也成功"这类幂等断言，避免碰真实文档） */
    private static final long ABSENT_SPU_ID = FAKE_SPU_ID_BASE + 1;

    /** mark-dirty 用例自己的 spuId（**只是待同步标记**，不写索引，所以不会给索引留垃圾） */
    private static final long MARK_DIRTY_SPU_A = FAKE_SPU_ID_BASE + 700;
    private static final long MARK_DIRTY_SPU_B = FAKE_SPU_ID_BASE + 701;

    /** 待同步集合是**共享**的（单体/活着的 search/其它套件都写它），断言要按它的取出语义来写 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.mall.search.service.ProductSearchService productSearchService;

    // ==================================================================
    // ① 无令牌 → 403（8 个端点逐个 + 副作用断言）
    // ==================================================================

    @Test
    @DisplayName("[内部接口/无令牌] 8 个端点全部 403，且索引文档数不变（不产生副作用）")
    void allInternalEndpoints_withoutToken_are403_andSideEffectFree() throws Exception {
        long docsBefore = docCount();

        assert403(anonymousPost("/internal/v1/search/products", "{\"keyword\":\"耳机\"}"));
        assert403(anonymousPost("/internal/v1/search/reindex", "{}"));
        assert403(get("/internal/v1/search/status"));
        assert403(anonymousPost("/internal/v1/search/sync/1", "{}"));
        assert403(delete("/internal/v1/search/product/1"));
        assert403(anonymousPost("/internal/v1/search/sync-by-brand/1", "{}"));
        // P6-5 #5 新增的两个端点也必须**同样**被闸门拦住（新端点漏鉴权是本项目最贵的一类错）
        assert403(anonymousPost("/internal/v1/search/mark-dirty", "{\"spuIds\":[1]}"));
        assert403(get("/internal/v1/search/product/1"));

        assertEquals(docsBefore, docCount(),
                "无令牌的 reindex/delete 不得改动索引（闸门在 handler **之前**：P1 的教训）");
        // ⚠️ mark-dirty 的副作用在 Redis 上（不是索引），单独用"待同步集合里不该有我那个 id"来钉：
        //    否则"新端点没鉴权"会以"标记被写进去了"的形式静默发生。
        assertFalse(drainMineAndRestoreOthers(MARK_DIRTY_SPU_A).contains(MARK_DIRTY_SPU_A),
                "无令牌的 mark-dirty 不该把 spuId 写进待同步集合");
    }

    @Test
    @DisplayName("[内部接口/错令牌] 带了但不匹配的令牌同样 403（证明闸门真的在校验，而不是碰巧")
    void wrongToken_is403() throws Exception {
        MvcResult r = mockMvc.perform(get("/internal/v1/search/status")
                        .header(TOKEN_HEADER, "definitely-not-the-configured-token"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(403, codeOf(body(r)), "错误令牌必须 403");
    }

    @Test
    @DisplayName("[内部接口/令牌来自配置] 用**运行时配置的那把**令牌可正常调用（本基类不覆盖令牌）")
    void configuredToken_works() throws Exception {
        assertTrue(internalToken != null && !internalToken.isBlank(),
                "运行时必须配置了 mall.internal.token（dev profile 有；未配置时内部接口一律 403 是正确行为）");
        MvcResult r = mockMvc.perform(get("/internal/v1/search/status").header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(r)), "带配置里的令牌应当业务成功");
    }

    // ==================================================================
    // ② 带令牌 → 真数据（自检 / 检索）
    // ==================================================================

    @Test
    @DisplayName("[内部接口/status] 索引名/文档数/分词器都是真值（docCount == ES 实测）")
    void status_withToken_returnsRealValues() throws Exception {
        MvcResult r = mockMvc.perform(get("/internal/v1/search/status").header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "带令牌必须业务成功");
        assertEquals(INDEX, JsonPath.read(b, "$.data.index"), "索引名恒为 mall_product（不改名）");
        long esDocs = docCount();
        assertTrue(esDocs > 0, "ES 里应当有文档（本机真实语料）");
        assertEquals(esDocs, ((Number) JsonPath.read(b, "$.data.docCount")).longValue(),
                "status.docCount 必须等于 ES 实测文档数");
        assertEquals("smartcn", JsonPath.read(b, "$.data.titleAnalyzer"),
                "分词器自检值必须是 smartcn（本机已装 analysis-smartcn，索引 mapping 也是它）");
        assertTrue(((Number) JsonPath.read(b, "$.data.pendingCount")).longValue() >= 0,
                "Redis 在跑时 pendingCount 应当 >= 0（-1 只在 Redis 不可用时出现）");
    }

    @Test
    @DisplayName("[内部接口/status] P6-5 #6 新增的 5 个集群字段是真值（单体 ping 转发后要与它逐字同形）")
    void status_clusterInfo_isReal() throws Exception {
        MvcResult r = mockMvc.perform(get("/internal/v1/search/status").header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "带令牌必须业务成功");

        // 值域断言（不写死具体集群名/节点名：那是环境事实，写死会让用例换个环境就假红）
        assertFalse(((String) JsonPath.read(b, "$.data.clusterName")).isBlank(),
                "ES 在跑时 clusterName 必须非空（单体 EsPingVO.clusterName 的取值来源）");
        assertFalse(((String) JsonPath.read(b, "$.data.nodeName")).isBlank(),
                "nodeName 必须非空（我们连上的那个节点，来自 info().name()）");
        String version = JsonPath.read(b, "$.data.esVersion");
        assertTrue(version != null && version.matches("\\d+\\.\\d+\\.\\d+.*"),
                "esVersion 必须是版本号形状（来自 info().version().number()），实际=" + version);
        String health = JsonPath.read(b, "$.data.healthStatus");
        assertTrue(java.util.List.of("green", "yellow", "red").contains(health),
                "healthStatus 必须是 green/yellow/red（来自 cluster().health().status().jsonValue()），实际=" + health);
        assertTrue(((Number) JsonPath.read(b, "$.data.numberOfNodes")).intValue() >= 1,
                "numberOfNodes 必须 >= 1（单节点集群也是 1；-1 只允许出现在 ES 不可达时）");

        // C1 回归：既有 4 个字段的名称/取值**一个字都没变**（P6-5 D4 只加字段）
        assertEquals(INDEX, JsonPath.read(b, "$.data.index"), "index 仍是 mall_product");
        assertEquals(docCount(), ((Number) JsonPath.read(b, "$.data.docCount")).longValue(),
                "docCount 语义未变（仍等于 ES 实测文档数）");
    }

    // ==================================================================
    // ②.五、P6-5 #5 的两个新端点（补空洞：标记待同步 / 按 id 取单文档）
    // ==================================================================

    @Test
    @DisplayName("[内部接口/mark-dirty] 标记进 Redis 待同步集合：added=新增条数，且定时任务能 drain 到")
    void markDirty_marksIntoRedisPendingSet() throws Exception {
        // 前置：把我这两个 id 从共享集合里清掉（否则 SADD 返回 0，"added=2"这条断言就失去意义）
        drainMineAndRestoreOthers(MARK_DIRTY_SPU_A, MARK_DIRTY_SPU_B);

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/mark-dirty",
                        "{\"spuIds\":[" + MARK_DIRTY_SPU_A + "," + MARK_DIRTY_SPU_B + "]}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "带令牌必须业务成功");
        assertEquals(2L, ((Number) JsonPath.read(b, "$.data.added")).longValue(),
                "两个新 id 都应当进集合（added = Redis SADD 的新增数），实际 body=" + b);
        assertEquals(java.util.Set.of("added"), ((java.util.Map<?, ?>) JsonPath.read(b, "$.data")).keySet(),
                "data 的形状是契约：只有 added 一个键");

        List<Long> drained = drainMineAndRestoreOthers(MARK_DIRTY_SPU_A, MARK_DIRTY_SPU_B);
        assertTrue(drained.contains(MARK_DIRTY_SPU_A) && drained.contains(MARK_DIRTY_SPU_B),
                "标记必须能被 drain 出来（drain 就是 ProductSearchSyncTask.flushPending 的入口；"
                        + "取不出来 ⇒ 定时任务永远看不到这些商品，索引静默停更）；实际取出=" + drained);
    }

    @Test
    @DisplayName("[内部接口/mark-dirty] 空/null/无 body ⇒ added:0 且 code=0（不是错误，也不是 -1）")
    void markDirty_emptyInput_isZeroWithoutError() throws Exception {
        assertMarkDirtyAdded("{\"spuIds\":[]}", 0L, "空数组");
        assertMarkDirtyAdded("{}", 0L, "字段缺失");
        assertMarkDirtyAdded("{\"spuIds\":null}", 0L, "字段为 null");
        assertMarkDirtyAdded("{\"spuIds\":[null,null]}", 0L, "全是 null 元素");

        // 完全没有请求体（@RequestBody(required=false)）：调用方漏传时也该是"没有要标记的"
        MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/internal/v1/search/mark-dirty").header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(r)), "无 body 也应当是 code=0（空列表是正常入参，不是错误）");
        assertEquals(0L, ((Number) JsonPath.read(body(r), "$.data.added")).longValue(),
                "无 body ⇒ added:0（**不是 -1**：-1 的含义是'Redis 不可用'，两者的区别必须能从响应里看出来）");
    }

    @Test
    @DisplayName("[内部接口/product/{id}] 返回索引里的真文档（12 个字段的键值对，_id = spuId）")
    void productById_returnsIndexedDocument() throws Exception {
        ProductSearchDoc real = anyIndexedDoc();
        assertNotNull(real, "索引里应当至少有一篇真实文档");
        long spuId = real.getSpuId();

        MvcResult r = mockMvc.perform(get("/internal/v1/search/product/" + spuId).header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "带令牌必须业务成功");
        assertEquals(spuId, ((Number) JsonPath.read(b, "$.data.spuId")).longValue(), "文档的 spuId 必须是被问的那个");
        assertEquals(real.getTitle(), JsonPath.read(b, "$.data.title"), "取回的必须是**索引里那份内容**（同一 spuId 的文档）");
        assertEquals(real.getStatus(), ((Number) JsonPath.read(b, "$.data.status")).intValue());
        assertEquals(real.getMinPrice(), ((Number) JsonPath.read(b, "$.data.minPrice")).longValue());
        assertEquals(java.util.Set.of("spuId", "title", "subtitle", "brandId", "brandName", "categoryId",
                        "minPrice", "sales", "totalStock", "status", "mainImage", "createTimeMillis"),
                ((java.util.Map<?, ?>) JsonPath.read(b, "$.data")).keySet(),
                "文档形状必须与索引 mapping 的 12 个字段一一对应（多一个少一个都说明契约漂了）");
    }

    @Test
    @DisplayName("[内部接口/product/{id}] 文档不存在 ⇒ code=0 + data:null（'不存在'是结论，不是错误）")
    void productById_absent_isNullWithoutError() throws Exception {
        MvcResult r = mockMvc.perform(get("/internal/v1/search/product/" + ABSENT_SPU_ID)
                        .header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "文档不存在不是接口错误（ES get 的 found=false）");
        assertNull(JsonPath.read(b, "$.data"), "不存在必须如实给 null，而不是抛错或编一个空文档");
    }

    @Test
    @DisplayName("[内部接口/products] 用**索引里真实存在的**标题检索能命中它自己（id 从索引读，不写死）")
    void searchProducts_hitsTheRealDocumentItWasBuiltFrom() throws Exception {
        ProductSearchDoc real = anyIndexedDoc();
        assertNotNull(real, "索引里应当至少有一篇文档");
        long spuId = real.getSpuId();
        String keyword = real.getTitle();
        assertNotNull(keyword, "真实文档应当有标题");

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"keyword\":\"" + keyword + "\",\"sort\":\"default\",\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b));
        List<Integer> ids = JsonPath.read(b, "$.data.spuIds");
        long total = ((Number) JsonPath.read(b, "$.data.total")).longValue();
        assertTrue(total >= 1, "按真实标题检索应至少命中 1 条（total=" + total + "）");
        assertTrue(ids.contains((int) spuId), "命中结果必须包含 " + spuId + "，实际=" + ids);
    }

    @Test
    @DisplayName("[内部接口/products] 分页不重叠、pageSize 被夹到 50（不信任调用方传参）")
    void searchProducts_pagingAndClamp() throws Exception {
        long docs = docCount();
        // ⚠️ 这里用**断言**而不是 assumeTrue：语料太少是"环境不对"，应当**报红**而不是静默跳过
        //    （assume 只允许出现在显式集成层；套件的 Skipped 必须保持 0）
        assertTrue(docs >= 6, "索引至少有 6 篇文档才能验证两页不重叠，实际=" + docs);

        MvcResult r1 = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"sort\":\"default\",\"pageNum\":1,\"pageSize\":3}"))
                .andExpect(status().isOk()).andReturn();
        List<Integer> page1 = JsonPath.read(body(r1), "$.data.spuIds");
        assertEquals(3, page1.size(), "pageSize=3 应当返回 3 个 id");

        MvcResult r2 = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"sort\":\"default\",\"pageNum\":2,\"pageSize\":3}"))
                .andExpect(status().isOk()).andReturn();
        List<Integer> page2 = JsonPath.read(body(r2), "$.data.spuIds");
        assertEquals(3, page2.size(), "第二页也应有 3 个 id");
        assertTrue(page1.stream().noneMatch(page2::contains), "两页不应有重复（分页要稳定）");

        MvcResult r3 = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"sort\":\"default\",\"pageNum\":1,\"pageSize\":999}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(((List<?>) JsonPath.read(body(r3), "$.data.spuIds")).size() <= 50, "pageSize 超上限应被夹到 50");
    }

    @Test
    @DisplayName("[内部接口/products] default 排序按 sales 倒序（分页稳定由 spuId 兜底）")
    void searchProducts_defaultSortIsSalesDesc() throws Exception {
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"sort\":\"default\",\"pageNum\":1,\"pageSize\":5}"))
                .andExpect(status().isOk()).andReturn();
        List<Integer> ids = JsonPath.read(body(r), "$.data.spuIds");
        assertTrue(ids.size() >= 2, "至少要有 2 条结果才能验证排序，实际=" + ids.size());
        int first = intFieldOf(ids.get(0), "sales");
        int second = intFieldOf(ids.get(1), "sales");
        assertTrue(first >= second, "default 排序应按 sales 倒序：first=" + first + " second=" + second);
    }

    // ==================================================================
    // ③ 索引自身：mapping 逐字 / 无别名 / 分片副本（**只读现成索引，不需要 product**）
    // ==================================================================

    @Test
    @DisplayName("[mapping] 12 个字段 / title=smartcn / subtitle 纯 text / mainImage=keyword(index:false)")
    void mapping_isVerbatim() throws Exception {
        Map<String, Property> props = currentMappingProperties();
        assertEquals(12, props.size(), "mapping 字段数必须是 12（与现状逐字一致），实际=" + props.keySet());

        assertTrue(props.get("title").isText(), "title 必须是 text");
        assertEquals("smartcn", props.get("title").text().analyzer(),
                "title 的分析器必须是 smartcn（实测线上就是这个值）");
        assertTrue(props.get("subtitle").isText(), "subtitle 必须是 text");
        assertNull(props.get("subtitle").text().analyzer(), "subtitle **不带** analyzer（纯 text，与现状一致）");

        assertTrue(props.get("mainImage").isKeyword());
        assertEquals(Boolean.FALSE, props.get("mainImage").keyword().index(),
                "mainImage 是 keyword 且 index:false（不参与检索，只用于展示）");
        assertTrue(props.get("brandName").isKeyword(), "brandName 必须是 keyword（精确筛选用）");

        assertTrue(props.get("spuId").isLong());
        assertTrue(props.get("categoryId").isLong());
        assertTrue(props.get("brandId").isLong());
        assertTrue(props.get("minPrice").isLong());
        assertTrue(props.get("createTimeMillis").isLong());
        assertTrue(props.get("sales").isInteger());
        assertTrue(props.get("totalStock").isInteger());
        assertTrue(props.get("status").isInteger());

        // 分片/副本在**嵌套的 index 对象**里（响应形状 {"settings":{"index":{...}}}）
        var indexSettings = elasticsearchClient.indices()
                .getSettings(s -> s.index(INDEX)).settings().get(INDEX).settings().index();
        assertEquals("1", indexSettings.numberOfShards(), "必须 1 分片");
        assertEquals("0", indexSettings.numberOfReplicas(), "必须 0 副本（单节点）");
    }

    @Test
    @DisplayName("[索引名] 恒为 mall_product，且**没有别名**")
    void indexNameAndNoAlias() throws Exception {
        var aliases = elasticsearchClient.indices().getAlias(a -> a.index(INDEX)).aliases();
        assertTrue(aliases.containsKey(INDEX), "索引 mall_product 必须存在");
        var aliasList = aliases.get(INDEX).aliases();
        assertTrue(aliasList == null || aliasList.isEmpty(),
                "mall_product 不得有任何别名（P6-2 不引入别名），实际=" + aliasList);
    }

    @Test
    @DisplayName("[索引内容] 真实语料的文档字段是齐的（title/sales/status/minPrice/totalStock/createTimeMillis）")
    void indexedRealDocuments_haveSaneFields() throws Exception {
        // 这条替代了原先写在 reindex 套件里的"重建后 1001 字段非空"断言：
        // 它读的是**现成索引**，不需要 product 参与，因此不会随 product 起停而红/绿漂移。
        ProductSearchDoc doc = anyIndexedDoc();
        assertNotNull(doc, "索引里应当至少有一篇真实文档");
        assertNotNull(doc.getSpuId(), "spuId 不能为空（它是文档 _id）");
        assertNotNull(doc.getTitle(), "title 不能为空");
        assertNotNull(doc.getStatus(), "status 不能为空");
        assertEquals(1, doc.getStatus(), "索引里只应有在架商品（status=1）");
        assertNotNull(doc.getMinPrice(), "minPrice 由 SKU 聚合而来，不能为空");
        assertNotNull(doc.getTotalStock(), "totalStock 由 SKU 聚合而来，不能为空");
        assertNotNull(doc.getCreateTimeMillis(), "createTimeMillis 是 newest 排序字段，不能为空");
        assertNotNull(doc.getSales(), "sales 是 default 排序字段，不能为空");
    }

    // ==================================================================
    // ④ 收尾：不留垃圾
    // ==================================================================

    @Test
    @DisplayName("[收尾] 假 spuId 段（9 开头）在索引里不应存在文档——测试不许留垃圾")
    void noJunkDocumentsLeftBehind() throws Exception {
        assertFalse(docExists(FAKE_SPU_ID_BASE), "假号段 " + FAKE_SPU_ID_BASE + " 不该有文档");
        assertFalse(docExists(ABSENT_SPU_ID), "不存在的 spuId 不该有文档");
    }

    // ---------- helpers ----------

    private void assert403(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult r = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        assertEquals(403, codeOf(body(r)), "无 X-Internal-Token 必须 403（body 里的 code）：" + builder);
    }

    /** 断言某次 mark-dirty 调用的 {@code data.added}（连同"HTTP 200 + code=0"一起钉住） */
    private void assertMarkDirtyAdded(String json, long expectedAdded, String what) throws Exception {
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/mark-dirty", json))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), what + " 必须是 code=0（空入参不是错误），body=" + b);
        assertEquals(expectedAdded, ((Number) JsonPath.read(b, "$.data.added")).longValue(),
                what + " 的 added 应当是 " + expectedAdded + "，body=" + b);
    }

    /**
     * 取出待同步集合里的成员，并把我**自己**的 id 拿走、把**别人的** id 原样放回去。
     *
     * <p>⚠️ 为什么必须"放回去"：{@code mall:es:pending} 是 **单体 / 活着的 mall-search / 其它套件共享**的
     * 兜底队列，SPOP 是"取走即不再返回"。把别人的待同步标记留在手里 = 那些商品的索引**静默停更**
     * （这是"测试干扰在跑的服务"的典型，P6-2 起本项目的所有相关套件都按这条纪律写）。
     *
     * @return 本次取出的**全部**成员（含别人的），供断言"我的 id 确实在集合里"
     */
    private List<Long> drainMineAndRestoreOthers(long... mine) {
        List<Long> drained = productSearchService.drainPending(1000);
        java.util.Set<Long> mineSet = java.util.Arrays.stream(mine).boxed()
                .collect(java.util.stream.Collectors.toSet());
        List<Long> others = drained.stream().filter(id -> !mineSet.contains(id)).toList();
        if (!others.isEmpty()) {
            productSearchService.markDirty(others);   // 只写 Redis 兜底集合（测试上下文里 MQ 是关的）
        }
        return drained;
    }

    // codeOf 已上移到 SearchTestSupport（共享支撑），这里不再各写一份

    /** 从索引里取一篇真实文档（按 sales 倒序取第一条，保证稳定拿得到） */
    private ProductSearchDoc anyIndexedDoc() throws Exception {
        SearchResponse<ProductSearchDoc> response = elasticsearchClient.search(s -> s
                        .index(INDEX)
                        .size(1)
                        .sort(SortOptions.of(so -> so.field(f -> f.field("spuId").order(SortOrder.Asc)))),
                ProductSearchDoc.class);
        return response.hits().hits().stream()
                .map(h -> h.source())
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** 按 spuId 读它自己的 sales（用于排序断言） */
    private int intFieldOf(long spuId, String field) throws Exception {
        SearchResponse<ProductSearchDoc> response = elasticsearchClient.search(s -> s
                        .index(INDEX).size(1)
                        .query(q -> q.term(t -> t.field("spuId").value(spuId))),
                ProductSearchDoc.class);
        ProductSearchDoc doc = response.hits().hits().stream()
                .map(h -> h.source()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        assertNotNull(doc, "文档 " + spuId + " 必须在索引里");
        return switch (field) {
            case "sales" -> doc.getSales() == null ? 0 : doc.getSales();
            default -> 0;
        };
    }

    private Map<String, Property> currentMappingProperties() throws Exception {
        Map<String, IndexMappingRecord> all = elasticsearchClient.indices()
                .getMapping(g -> g.index(INDEX)).mappings();
        TypeMapping mapping = all.get(INDEX).mappings();
        return mapping.properties();
    }
}
