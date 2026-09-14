package com.mall.product.internal;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>P6-2 新增端点</b> {@code POST /internal/v1/product/index-docs} 的真库测试。
 *
 * <h2>这个端点是干什么的</h2>
 * {@code mall-search} 没有 MySQL，索引文档的内容只能向商品域拉（P6-2 规格 §3）。
 * 三种取法（按 spuIds / 按 brandId / 全量分页）覆盖三种调用场景：
 * 单条同步、品牌改名批量重写、全量重建逐页拉。
 *
 * <h2>为什么断言必须与库内真值逐字段比对</h2>
 * 索引文档的字段口径（在架 SKU 的最低价/总库存聚合、品牌名、{@code createTimeMillis}）
 * 是**拆分前 {@code ProductSearchServiceImpl#buildDoc} 的知识**，搬到这里必须**逐字一致**——
 * 一旦某处口径漂了（例如把"只算 status=1 的 SKU"写成"算所有 SKU"），
 * 表现是"检索页的价格/库存与详情页不一致"，而且**不报错**。所以这里逐字段比 DB。
 *
 * <h2>双向验证（P1 的教训）</h2>
 * 无令牌 → {@code code=403}；带令牌 → 真数据。只测 403 等于没测（它在 handler 之前就通过）。
 * 另外单测一条"三种取法只能给一种"（给多了必须 400，**不猜优先级**——猜错的后果是把
 * "品牌批量重写"悄悄当成"按 spuId 取"，索引里少一大批文档且无人发现）。
 */
@Transactional
@Rollback
class InternalProductIndexDocsMySqlTest extends ProductTestBase {

    private static final String PATH = "/internal/v1/product/index-docs";
    /** 真实种子：SPU 1001（示例商品·无线降噪耳机 Pro，status=1，2 个在架 SKU） */
    private static final long SPU_ID = 1001L;

    // ==================================================================
    // 双向
    // ==================================================================

    @Test
    @DisplayName("[index-docs/无令牌] 403，且不返回任何文档")
    void withoutToken_is403() throws Exception {
        MvcResult r = mockMvc.perform(anonymousPost(PATH, "{\"spuIds\":[" + SPU_ID + "]}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(403, codeOf(b), "无 X-Internal-Token 必须 403（body 里的 code）");
        assertEquals("内部接口鉴权失败", JsonPath.read(b, "$.message"));
    }

    // ==================================================================
    // 取法一：按 spuIds（只返回在架且未删除的）
    // ==================================================================

    @Test
    @DisplayName("[index-docs/spuIds] 文档字段与库内真值逐字一致（含 SKU 聚合与 createTimeMillis）")
    void bySpuIds_matchesDatabase() throws Exception {
        MvcResult r = mockMvc.perform(internalPost(PATH, "{\"spuIds\":[" + SPU_ID + "]}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "带令牌必须成功");
        assertEquals(1, ((List<?>) JsonPath.read(b, "$.data.docs")).size(), "在架的真实 SPU 应产生 1 篇文档");

        // 库内真值
        String dbTitle = stringOf("SELECT title FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", SPU_ID);
        Integer dbStatus = intOf("SELECT status FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", SPU_ID);
        Integer dbSales = intOf("SELECT sales FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", SPU_ID);
        Long dbMinPrice = jdbcTemplate.queryForObject("SELECT MIN(price) FROM " + EXPECTED_SCHEMA
                + ".pms_sku WHERE spu_id = ? AND deleted = 0 AND status = 1", Long.class, SPU_ID);
        Long dbTotalStock = jdbcTemplate.queryForObject("SELECT SUM(stock) FROM " + EXPECTED_SCHEMA
                + ".pms_sku WHERE spu_id = ? AND deleted = 0 AND status = 1", Long.class, SPU_ID);
        Long dbCreateMillis = jdbcTemplate.queryForObject(
                "SELECT UNIX_TIMESTAMP(create_time) * 1000 FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?",
                Long.class, SPU_ID);

        assertEquals(SPU_ID, ((Number) JsonPath.read(b, "$.data.docs[0].spuId")).longValue());
        assertEquals(dbTitle, JsonPath.read(b, "$.data.docs[0].title"), "title 必须来自 pms_spu");
        assertEquals(dbStatus, ((Number) JsonPath.read(b, "$.data.docs[0].status")).intValue());
        assertEquals(dbSales, ((Number) JsonPath.read(b, "$.data.docs[0].sales")).intValue());
        assertEquals(dbMinPrice.longValue(), ((Number) JsonPath.read(b, "$.data.docs[0].minPrice")).longValue(),
                "minPrice 必须是**在架 SKU** 的最低售价（口径与单体 buildDoc 一致）");
        assertEquals(dbTotalStock.intValue(), ((Number) JsonPath.read(b, "$.data.docs[0].totalStock")).intValue(),
                "totalStock 必须是**在架 SKU** 的库存合计");
        // createTimeMillis：用本地时区换算（与单体 ZoneId.systemDefault() 一致）——允许 1 秒误差（DB 精度/时区边界）
        long millis = ((Number) JsonPath.read(b, "$.data.docs[0].createTimeMillis")).longValue();
        assertTrue(Math.abs(millis - dbCreateMillis) <= 1000,
                "createTimeMillis 应等于 create_time 的毫秒值，json=" + millis + " db=" + dbCreateMillis);
        // 契约字段必须齐全（search 侧按这些名字反序列化）
        for (String field : List.of("spuId", "title", "subtitle", "mainImage", "categoryId", "brandId",
                "brandName", "minPrice", "totalStock", "sales", "status", "createTimeMillis")) {
            assertTrue(b.contains("\"" + field + "\""), "索引文档必须带字段 " + field + "（与 ES mapping 的 12 个字段对应）");
        }
    }

    @Test
    @DisplayName("[index-docs/spuIds] 不存在/已下架的 spu **不产生文档**（调用方据此删除索引文档）")
    void bySpuIds_offShelfOrAbsent_producesNoDoc() throws Exception {
        // 造一个下架商品（本用例 @Rollback，不会留在库里）
        jdbcTemplate.update("INSERT INTO " + EXPECTED_SCHEMA
                + ".pms_spu (category_id, title, subtitle, main_image, status, recommended, sales, deleted, create_time, update_time)"
                + " VALUES (12, ?, '', '', 0, 0, 0, 0, NOW(), NOW())", "下架索引测试_" + suffix);
        Long offShelfId = jdbcTemplate.queryForObject("SELECT id FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE title = ?", Long.class, "下架索引测试_" + suffix);

        MvcResult r = mockMvc.perform(internalPost(PATH,
                        "{\"spuIds\":[" + SPU_ID + "," + offShelfId + ",99999999]}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b));
        List<Integer> ids = JsonPath.read(b, "$.data.docs[*].spuId");
        assertEquals(List.of((int) SPU_ID), ids,
                "只有**在架**的 " + SPU_ID + " 应产生文档；下架的与不存在的都不该出现"
                        + "（调用方用『请求的 id − 返回的 id』删除索引文档）");
    }

    // ==================================================================
    // 取法二/三：按品牌、分页
    // ==================================================================

    @Test
    @DisplayName("[index-docs/brandId] 只返回该品牌**在架**商品；totalInShelf 与库内一致")
    void byBrand_onlyOnShelfOfThatBrand() throws Exception {
        MvcResult r = mockMvc.perform(internalPost(PATH, "{\"brandId\":1}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b));
        long dbOnShelfOfBrand = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE brand_id = 1 AND status = 1 AND deleted = 0");
        assertEquals((int) dbOnShelfOfBrand, ((List<?>) JsonPath.read(b, "$.data.docs")).size(),
                "返回的文档数必须等于该品牌下在架商品数");
        long dbOnShelfTotal = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE status = 1 AND deleted = 0");
        assertEquals(dbOnShelfTotal, ((Number) JsonPath.read(b, "$.data.totalInShelf")).longValue(),
                "totalInShelf 必须等于在架商品总数");
    }

    @Test
    @DisplayName("[index-docs/分页] spuId 升序、页不重叠、totalInShelf 一致、pageSize 被夹取")
    void page_isStableAndOrdered() throws Exception {
        long dbOnShelfTotal = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE status = 1 AND deleted = 0");
        assertTrue(dbOnShelfTotal > 3, "在架商品应多于 3 个（否则分页断言无意义）");

        MvcResult p1 = mockMvc.perform(internalPost(PATH, "{\"pageNum\":1,\"pageSize\":2}"))
                .andExpect(status().isOk()).andReturn();
        List<Integer> ids1 = JsonPath.read(body(p1), "$.data.docs[*].spuId");
        assertEquals(2, ids1.size(), "第 1 页应有 2 条");
        assertTrue(ids1.get(0) < ids1.get(1), "文档必须按 spuId **升序**（翻页才稳定）");
        assertEquals(dbOnShelfTotal, ((Number) JsonPath.read(body(p1), "$.data.totalInShelf")).longValue());

        MvcResult p2 = mockMvc.perform(internalPost(PATH, "{\"pageNum\":2,\"pageSize\":2}"))
                .andExpect(status().isOk()).andReturn();
        List<Integer> ids2 = JsonPath.read(body(p2), "$.data.docs[*].spuId");
        assertEquals(2, ids2.size(), "第 2 页应有 2 条");
        assertTrue(ids1.stream().noneMatch(ids2::contains), "两页不得重叠");
        assertTrue(ids2.get(0) > ids1.get(1), "第 2 页的 id 必须都大于第 1 页（升序 + 连续翻页）");

        // pageSize 超上限 → 夹到 500（不信任调用方传参）
        MvcResult big = mockMvc.perform(internalPost(PATH, "{\"pageNum\":1,\"pageSize\":99999}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(((List<?>) JsonPath.read(body(big), "$.data.docs")).size() <= 500, "pageSize 应被夹到 500");
    }

    // ==================================================================
    // 契约边界：三种取法只能给一种
    // ==================================================================

    @Test
    @DisplayName("[index-docs/契约] 既不给参数 / 给两种取法 → 400（不猜优先级）")
    void exactlyOneMode_required() throws Exception {
        MvcResult none = mockMvc.perform(internalPost(PATH, "{}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(400, codeOf(body(none)), "什么取法都不给必须 400");
        assertTrue(((String) JsonPath.read(body(none), "$.message")).contains("只能给一种"));

        MvcResult both = mockMvc.perform(internalPost(PATH, "{\"spuIds\":[1001],\"brandId\":1}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(400, codeOf(body(both)), "同时给两种取法必须 400（静默选一个会悄悄漏掉一大批文档）");

        MvcResult pageAndBrand = mockMvc.perform(internalPost(PATH, "{\"brandId\":1,\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(400, codeOf(body(pageAndBrand)));
    }

    private static int codeOf(String body) {
        return ((Number) JsonPath.read(body, "$.code")).intValue();
    }
}
