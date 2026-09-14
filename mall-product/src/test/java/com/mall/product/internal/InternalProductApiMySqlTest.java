package com.mall.product.internal;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部接口（{@code /internal/v1/**}）的**双向**验证：无令牌 → 403；带令牌 → 取到**真数据**。
 *
 * <h2>为什么必须"双向"（P1 的教训，P6-1 规格 §9 第 5 条点名）</h2>
 * 只测"无令牌 → 403"是**在 handler 之前**就通过的：端点写错路径、注入错 service、
 * 甚至整个 Controller 忘了写，用例照样绿——因为它断言的是拦截器。
 * 所以这里每一条都成对：
 * <ul>
 *   <li>无令牌：body {@code code=403}，**并且**断言"这次调用没有产生任何副作用"
 *       （库存没动、没有新流水）——这才证明闸门拦在了 handler **之前**；</li>
 *   <li>带令牌：把响应里的值与**数据库真值**逐字段比对（不是"非空就行"）。</li>
 * </ul>
 *
 * <h2>关于 "403" 的准确含义</h2>
 * 本服务的响应约定是"HTTP 恒 200，业务结果看 body 的 code"（{@code ApiResponse}），
 * 拦截器抛的 {@code BusinessException(403)} 经 {@code GlobalExceptionHandler} 变成
 * HTTP 200 + {@code code=403}。所以断言写的是 body 里的 code=403，
 * 并额外断言 HTTP 状态是 200 ——**这两个一起**才是这条契约的完整表述。
 *
 * <h2>端点数：7 个（不是 8 个）</h2>
 * 单体 {@code internal/InternalProductController} 实测是 **7 个映射**
 * （3 个商品读 + 1 个首页聚合 + 3 个库存写），本类逐个覆盖。
 * ⚠️ P6-1 规格 §9 第 5 条写的是"8 个端点"，与 §6 自己列的 7 个映射对不上——
 * 这是一处**规格内部不一致**，以单体实现（7 个）为准，并已在 P6-1 汇报里列为待确认项。
 */
class InternalProductApiMySqlTest extends ProductTestBase {

    /** 真实种子数据：SPU 1001（示例商品·无线降噪耳机 Pro）及其 SKU 2001/2002 */
    private static final long SPU_ID = 1001L;
    private static final long SKU_ID = 2001L;
    private static final long SKU_ID_2 = 2002L;

    // ==================================================================
    // ① 无令牌 → 403（7 个端点逐个 + 副作用断言）
    // ==================================================================

    @Test
    @DisplayName("[内部接口/无令牌] 7 个端点全部 403，且**不产生任何副作用**")
    void allInternalEndpoints_withoutToken_are403_andSideEffectFree() throws Exception {
        String ids = "{\"ids\":[" + SKU_ID + "]}";
        String stockBody = "{\"orderNo\":\"nope\",\"lines\":[{\"skuId\":" + SKU_ID
                + ",\"spuId\":" + SPU_ID + ",\"quantity\":1}]}";

        int stockBefore = stockOf(SKU_ID);
        long logBefore = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_sku_stock_log");

        // 读端点（4 条）：路径写错的话这里依然会是 403（拦截器在 handler 之前），
        // 所以"路径是否真的存在"由下面带令牌的那组用例负责证明——这正是"双向"的意义。
        assert403(anonymousPost("/internal/v1/product/sku/batch", ids));
        assert403(anonymousPost("/internal/v1/product/sku/min-price/batch", ids));
        assert403(anonymousPost("/internal/v1/product/spu/batch", ids));
        assert403(get("/internal/v1/product/home-feed"));

        // 写端点（3 条）：必须**真的没有改库**
        assert403(anonymousPost("/internal/v1/stock/reserve", stockBody));
        assert403(anonymousPost("/internal/v1/stock/release",
                "{\"orderNo\":\"nope\",\"changeType\":2,\"lines\":[{\"skuId\":" + SKU_ID
                        + ",\"spuId\":" + SPU_ID + ",\"quantity\":1}]}"));
        assert403(anonymousPost("/internal/v1/stock/sales/increment", stockBody));

        // 副作用断言：闸门在 handler **之前**（P1 的教训：只看 403 会掩盖端点写错）
        assertEquals(stockBefore, stockOf(SKU_ID), "无令牌的 reserve 不得改动库存");
        assertEquals(logBefore, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_sku_stock_log"),
                "无令牌的库存写端点不得留下流水");
    }

    @Test
    @DisplayName("[内部接口/错令牌] 带了但不匹配的令牌同样 403（不是放行、也不是 401）")
    void wrongToken_is403() throws Exception {
        MvcResult r = mockMvc.perform(post("/internal/v1/product/spu/batch")
                        .header(TOKEN_HEADER, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + SPU_ID + "]}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(403, codeOf(body(r)), "令牌不匹配必须 403");
    }

    // ==================================================================
    // ② 带令牌 → 真数据（7 个端点逐个，与库内真值比对）
    // ==================================================================

    @Test
    @DisplayName("[内部接口/带令牌] sku/batch + min-price/batch + spu/batch 取到与库内逐字段一致的数据")
    void readEndpoints_withToken_returnRealData() throws Exception {
        // ---- ① /product/sku/batch ----
        MvcResult skuResult = mockMvc.perform(internalPost("/internal/v1/product/sku/batch",
                        "{\"ids\":[" + SKU_ID + "," + SKU_ID_2 + "]}"))
                .andExpect(status().isOk()).andReturn();
        String skuBody = body(skuResult);
        assertEquals(0, codeOf(skuBody), "带令牌必须业务成功");
        assertEquals(2, ((List<?>) JsonPath.read(skuBody, "$.data")).size(), "两个已存在的 SKU 都应返回");

        Integer dbStock = intOf("SELECT stock FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE id = ?", SKU_ID);
        Long dbPrice = jdbcTemplate.queryForObject(
                "SELECT price FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE id = ?", Long.class, SKU_ID);
        Long dbSpuIdOfSku = jdbcTemplate.queryForObject(
                "SELECT spu_id FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE id = ?", Long.class, SKU_ID);

        assertEquals(dbStock.intValue(), numberAt(skuBody, "$.data[?(@.id==" + SKU_ID + ")].stock"),
                "sku/batch 的 stock 必须等于 pms_sku.stock（真数据，不是形状对了就行）");
        assertEquals(dbPrice.longValue(), longAt(skuBody, "$.data[?(@.id==" + SKU_ID + ")].price"),
                "price 同样逐字段比对");
        assertEquals(dbSpuIdOfSku.longValue(), longAt(skuBody, "$.data[?(@.id==" + SKU_ID + ")].spuId"));

        // ---- ② /product/sku/min-price/batch（Map<spuId, minPrice>，key 是字符串形式的 spuId）----
        MvcResult minPriceResult = mockMvc.perform(internalPost("/internal/v1/product/sku/min-price/batch",
                        "{\"ids\":[" + SPU_ID + "]}"))
                .andExpect(status().isOk()).andReturn();
        String minPriceBody = body(minPriceResult);
        Long dbMin = jdbcTemplate.queryForObject(
                "SELECT MIN(price) FROM " + EXPECTED_SCHEMA
                        + ".pms_sku WHERE spu_id = ? AND status = 1 AND deleted = 0", Long.class, SPU_ID);
        assertNotNull(dbMin, "种子数据里 SPU 1001 应当有启用 SKU（否则本用例失去意义）");
        assertEquals(dbMin.longValue(), ((Number) JsonPath.read(minPriceBody, "$.data." + SPU_ID)).longValue(),
                "最低价必须等于 MIN(price)（只看 status=1 的 SKU）");

        // ---- ③ /product/spu/batch ----
        MvcResult spuResult = mockMvc.perform(internalPost("/internal/v1/product/spu/batch",
                        "{\"ids\":[" + SPU_ID + "]}"))
                .andExpect(status().isOk()).andReturn();
        String spuBody = body(spuResult);
        assertEquals(stringOf("SELECT title FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", SPU_ID),
                stringAt(spuBody, "$.data[?(@.id==" + SPU_ID + ")].title"),
                "spu/batch 的 title 必须是库里的真标题");
        assertEquals(intOf("SELECT sales FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", SPU_ID).intValue(),
                numberAt(spuBody, "$.data[?(@.id==" + SPU_ID + ")].sales"));

        // ---- ④ /product/home-feed ----
        MvcResult feed3 = mockMvc.perform(get("/internal/v1/product/home-feed").param("size", "3")
                        .header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk()).andReturn();
        String feedBody = body(feed3);
        assertEquals(0, codeOf(feedBody));
        assertTrue(((List<?>) JsonPath.read(feedBody, "$.data.categories")).size() > 0,
                "类目树必须非空（种子数据有 69 个类目）");
        assertTrue(((List<?>) JsonPath.read(feedBody, "$.data.hotProducts")).size() <= 3, "每区块条数受 size 夹取");
        assertTrue(((List<?>) JsonPath.read(feedBody, "$.data.newProducts")).size() <= 3, "同上");
    }

    @Test
    @DisplayName("[内部接口/带令牌] home-feed 的 size 非法值被本域夹取（>50 → 默认 8）")
    void homeFeed_clampsSize() throws Exception {
        MvcResult r = mockMvc.perform(get("/internal/v1/product/home-feed").param("size", "9999")
                        .header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b));
        assertTrue(((List<?>) JsonPath.read(b, "$.data.hotProducts")).size() <= 8,
                "非法 size 应回落默认 8（不信任调用方传参，避免一次请求拖出整张货架）");
    }

    @Test
    @DisplayName("[内部接口/带令牌] stock/reserve：真扣库存 + 写流水（before/after 与库内一致）")
    void reserve_withToken_deductsStockAndWritesLog() throws Exception {
        rememberStock(SKU_ID);
        int before = stockOf(SKU_ID);
        String orderNo = newOrderNo("r");

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/stock/reserve",
                        "{\"orderNo\":\"" + orderNo + "\",\"lines\":[{\"skuId\":" + SKU_ID
                                + ",\"spuId\":" + SPU_ID + ",\"quantity\":2}]}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(r)), "reserve 应成功");

        assertEquals(before - 2, stockOf(SKU_ID), "库存应真的减 2（新库真值）");
        assertEquals(1, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                        + ".pms_sku_stock_log WHERE order_no = ? AND change_type = 1 AND delta = -2",
                orderNo), "应恰好一条下单扣减流水，delta = -2");
        assertEquals(before, intOf("SELECT before_stock FROM " + EXPECTED_SCHEMA
                + ".pms_sku_stock_log WHERE order_no = ?", orderNo).intValue(),
                "流水的 before_stock 是扣减前的真实库存");
        assertEquals(before - 2, intOf("SELECT after_stock FROM " + EXPECTED_SCHEMA
                + ".pms_sku_stock_log WHERE order_no = ?", orderNo).intValue(),
                "after_stock 是扣减后的真实库存（更新后再读）");
    }

    @Test
    @DisplayName("[内部接口/带令牌] stock/release：回补库存 + 按 changeType 写流水文案")
    void release_withToken_restoresStock() throws Exception {
        rememberStock(SKU_ID);
        String orderNo = newOrderNo("l");

        // 先扣 3
        mockMvc.perform(internalPost("/internal/v1/stock/reserve",
                        "{\"orderNo\":\"" + orderNo + "\",\"lines\":[{\"skuId\":" + SKU_ID
                                + ",\"spuId\":" + SPU_ID + ",\"quantity\":3}]}"))
                .andExpect(status().isOk());
        int afterDeduct = stockOf(SKU_ID);

        // 再按 changeType=3（超时关单回补）回补 3
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/stock/release",
                        "{\"orderNo\":\"" + orderNo + "\",\"changeType\":3,\"lines\":[{\"skuId\":" + SKU_ID
                                + ",\"spuId\":" + SPU_ID + ",\"quantity\":3}]}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(r)), "release 应成功");

        assertEquals(afterDeduct + 3, stockOf(SKU_ID), "库存应真的回补 3");
        assertEquals(1, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                        + ".pms_sku_stock_log WHERE order_no = ? AND change_type = 3 AND delta = 3",
                orderNo), "应恰好一条回补流水，delta = +3");
        assertEquals("超时关单回补", stringOf("SELECT remark FROM " + EXPECTED_SCHEMA
                        + ".pms_sku_stock_log WHERE order_no = ? AND change_type = 3", orderNo),
                "流水文案与改造前逐字一致（change_type=3 → 超时关单回补）");
    }

    @Test
    @DisplayName("[内部接口/带令牌] stock/sales/increment：SKU 与 SPU 销量同时累加")
    void incrementSales_withToken_bumpsSkuAndSpuSales() throws Exception {
        rememberStock(SKU_ID);          // 含 sku.sales 基线
        rememberSpuSales(SPU_ID);       // 含 spu.sales 基线
        int skuSalesBefore = skuSalesOf(SKU_ID);
        int spuSalesBefore = spuSalesOf(SPU_ID);
        String orderNo = newOrderNo("s");

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/stock/sales/increment",
                        "{\"orderNo\":\"" + orderNo + "\",\"lines\":[{\"skuId\":" + SKU_ID
                                + ",\"spuId\":" + SPU_ID + ",\"quantity\":4}]}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(r)), "incrementSales 应成功");

        assertEquals(skuSalesBefore + 4, skuSalesOf(SKU_ID), "SKU 销量应累加 4");
        assertEquals(spuSalesBefore + 4, spuSalesOf(SPU_ID), "SPU 销量应同时累加 4（同一事务）");
        assertEquals(0L, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                        + ".pms_sku_stock_log WHERE order_no = ?", orderNo),
                "累加销量**不写**库存流水（与改造前一致：它不是库存变更）");
    }

    @Test
    @DisplayName("[内部接口/带令牌] 库存不足时返回逐字文案 409「商品库存不足：<标题>」")
    void reserve_insufficientStock_returnsExactC1Message() throws Exception {
        rememberStock(SKU_ID);
        Long spuId = jdbcTemplate.queryForObject(
                "SELECT spu_id FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE id = ?", Long.class, SKU_ID);
        String title = stringOf("SELECT title FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", spuId);
        int stock = stockOf(SKU_ID);

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/stock/reserve",
                        "{\"orderNo\":\"" + newOrderNo("f") + "\",\"lines\":[{\"skuId\":" + SKU_ID
                                + ",\"spuId\":" + spuId + ",\"quantity\":" + (stock + 1) + "}]}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(409, codeOf(b), "原子条件 UPDATE 影响 0 行 → 409");
        assertEquals("商品库存不足：" + title, JsonPath.read(b, "$.message"),
                "文案必须逐字（C1）：注意这不是 trade 预检那条「库存不足：<标题>」，两条不能合并");
        assertEquals(stock, stockOf(SKU_ID), "失败的扣减不得改动库存");
    }

    // ==================================================================
    // P6-4 新增：商品看板统计（在架数 / 热度榜）—— 单体侧原本直读 pms_spu，术后走这两个端点
    // ==================================================================

    @Test
    @DisplayName("[P6-4/统计] 两个新端点在无令牌时同样 403（闸门覆盖到新端点，不是只覆盖老端点）")
    void statsEndpoints_withoutToken_are403() throws Exception {
        assert403(get("/internal/v1/product/stat/enabled-count"));
        assert403(get("/internal/v1/product/stat/top-sales").param("limit", "3"));
    }

    @Test
    @DisplayName("[P6-4/统计] 在架商品数 == 直接 SQL 计数（status=1 且未删除）")
    void enabledCount_matchesSql() throws Exception {
        long expected = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE deleted = 0 AND status = 1");

        MvcResult r = mockMvc.perform(get("/internal/v1/product/stat/enabled-count").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(0, codeOf(b), "带令牌必须业务成功，body=" + b);
        assertEquals(expected, ((Number) JsonPath.read(b, "$.data")).longValue(),
                "在架数与库内计数必须一致（口径：status=1 且 deleted=0）");
    }

    @Test
    @DisplayName("[P6-4/统计] 热度榜：销量倒序、limit 夹取到 [1,20]、缺省 10")
    void topSales_isSortedAndClamped() throws Exception {
        // ① 缺省 limit=10
        MvcResult dflt = mockMvc.perform(get("/internal/v1/product/stat/top-sales").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(dflt)), "带令牌必须业务成功");
        List<Number> defaultIds = JsonPath.read(body(dflt), "$.data[*].id");
        assertTrue(defaultIds.size() <= 10, "缺省 limit 必须 <= 10，实际 " + defaultIds.size());

        // ② 夹取：传 999 ⇒ 最多 20（**不信任调用方传参**，与契约注释一致）
        MvcResult big = mockMvc.perform(get("/internal/v1/product/stat/top-sales")
                        .header(TOKEN_HEADER, TOKEN).param("limit", "999"))
                .andExpect(status().isOk()).andReturn();
        List<Number> bigIds = JsonPath.read(body(big), "$.data[*].id");
        assertTrue(bigIds.size() <= 20, "limit=999 必须被夹到 20，实际 " + bigIds.size());
        assertTrue(bigIds.size() >= Math.min(20, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                        + ".pms_spu WHERE deleted = 0 AND status = 1")),
                "在架商品 >= 20 时必须返回满 20 条，实际 " + bigIds.size());

        // ③ 夹取下界：传 0 / 负数 ⇒ 至少 1 条（不是 0 条）
        for (String v : new String[]{"0", "-5"}) {
            MvcResult small = mockMvc.perform(get("/internal/v1/product/stat/top-sales")
                            .header(TOKEN_HEADER, TOKEN).param("limit", v))
                    .andExpect(status().isOk()).andReturn();
            List<Number> ids = JsonPath.read(body(small), "$.data[*].id");
            assertEquals(1, ids.size(), "limit=" + v + " 必须被夹到 1（下界），实际 " + ids.size());
        }

        // ④ 排序：返回项的 sales 必须非递增（这是"热度榜"的定义）
        List<Integer> sales = JsonPath.read(body(big), "$.data[*].sales");
        for (int i = 1; i < sales.size(); i++) {
            assertTrue(sales.get(i - 1) >= sales.get(i),
                    "销量必须倒序：第 " + (i - 1) + " 项 " + sales.get(i - 1) + " < 第 " + i + " 项 " + sales.get(i));
        }
        // ⑤ 形状：字段口径照 SpuSnapshotVO（id/title/subtitle/mainImage/sales/status）
        MvcResult one = mockMvc.perform(get("/internal/v1/product/stat/top-sales")
                        .header(TOKEN_HEADER, TOKEN).param("limit", "1"))
                .andExpect(status().isOk()).andReturn();
        String oneBody = body(one);
        assertTrue(longAt(oneBody, "$.data[*].id") > 0, "必须能读到 id");
        assertTrue(!stringAt(oneBody, "$.data[*].title").isBlank(),
                "title 必须非空（快照字段口径照 SpuSnapshotVO，不能返回 null/空串）");
        numberAt(oneBody, "$.data[*].sales");
        numberAt(oneBody, "$.data[*].status");
    }

    // ---------- helpers ----------

    private void assert403(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult r = mockMvc.perform(builder)
                .andExpect(status().isOk())   // HTTP 恒 200（本项目的响应约定）
                .andReturn();
        assertEquals(403, codeOf(body(r)), "无 X-Internal-Token 必须 403（body 里的 code）：" + builder);
    }

    private static int codeOf(String body) {
        return ((Number) JsonPath.read(body, "$.code")).intValue();
    }

    /** 取 jsonpath 过滤表达式的第一个命中项（{@code $.data[?(@.id==N)].field} 返回的是数组） */
    private static Object firstOf(String body, String path) {
        Object v = JsonPath.read(body, path);
        if (v instanceof List<?> list) {
            assertTrue(!list.isEmpty(), "jsonpath 没有命中任何元素: " + path);
            return list.get(0);
        }
        return v;
    }

    private static int numberAt(String body, String path) {
        return ((Number) firstOf(body, path)).intValue();
    }

    private static long longAt(String body, String path) {
        return ((Number) firstOf(body, path)).longValue();
    }

    private static String stringAt(String body, String path) {
        return String.valueOf(firstOf(body, path));
    }
}
