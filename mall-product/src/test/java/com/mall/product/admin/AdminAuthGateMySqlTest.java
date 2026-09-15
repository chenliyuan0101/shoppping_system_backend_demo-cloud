package com.mall.product.admin;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.JsonKit;

/**
 * <b>后台端点鉴权闸套件（P6-1b）</b>：{@code /api/admin/**} 在本服务内是否真的拦得住。
 *
 * <h2>为什么需要一个专门的套件</h2>
 * 之前这里是**已知缺口**：直连 8102 不带任何令牌 `GET /api/admin/product/page` 会返回
 * {@code {"code":0,...}}（匿名可调）。P6-1b 装了闸，本套件就是"缺口已闭合"的可执行证据。
 *
 * <h2>本套件用的令牌是怎么来的（以及为什么这样才可信）</h2>
 * 由 {@code support/AdminTokenMinter} **从零手写** HS256 签发，密钥取**运行时配置里的那一把**
 * （{@code mall.jwt.secret}）—— 不是被测类，也不是测试专用密钥：
 * <ul>
 *   <li>用独立实现签 ⇒ 不是"用被测物验证被测物"；</li>
 *   <li>用配置里的密钥签 ⇒ 同时证明了"部署配置的密钥能通过验签"（密钥配对是跨服务最容易踩的坑）；</li>
 *   <li>**没有**任何"测试期关闭鉴权"的开关：闸门在这些用例里与其他时候完全一样。</li>
 * </ul>
 *
 * <h2>只验拒绝是不够的（P1 的教训）</h2>
 * "只测 401"会在**handler 之前**就通过，从而掩盖"端点本身写错了/路由没接上"。
 * 所以每个拒绝场景都配一条**带正确令牌必须成功且取到真数据**的对照（见
 * {@link #withValidAdminToken_readEndpointsReturnRealData()}、
 * {@link #withValidAdminToken_writeEndpointReallyWrites()}）。
 *
 * <p>⚠️ 本套件**不覆盖**的三项（本服务做不到，属认证域）：管理员是否存在、账号是否被禁用、
 * 令牌版本号。详见 {@code config/AdminAuthInterceptor} 的类注释。
 */
class AdminAuthGateMySqlTest extends ProductTestBase {

    // ==================================================================
    // ① 无令牌 / 头格式不对 → 401「未登录」
    // ==================================================================

    @Test
    @DisplayName("[鉴权/无令牌] 三个读端点匿名调用全部 401「未登录」（缺口闭合的核心断言）")
    void readEndpoints_withoutToken_are401() throws Exception {
        for (String path : List.of("/api/admin/product/page", "/api/admin/brand/list",
                "/api/admin/category/tree")) {
            // ⚠️ 用 mockMvc.perform **直接**发请求（不走基类的 perform 包装）⇒ 真的是"没带令牌"
            MvcResult r = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
            String b = body(r);
            assertEquals(401, codeOf(b), path + " 无令牌必须 401，实际 body=" + b);
            assertEquals("未登录", messageOf(b), path + " 的 401 文案必须与单体逐字一致");
        }
    }

    @Test
    @DisplayName("[鉴权/头格式] Authorization 不是 \"Bearer xxx\" → 401「未登录」（三种畸形头）")
    void malformedAuthorizationHeader_is401() throws Exception {
        for (String header : List.of("Token abc.def.ghi", "Bearer ", "Bearer    ")) {
            MvcResult r = mockMvc.perform(get("/api/admin/product/page")
                    .header("Authorization", header)).andReturn();
            assertEquals(401, codeOf(body(r)), "畸形头 [" + header + "] 必须 401，body=" + body(r));
            assertEquals("未登录", messageOf(body(r)));
        }
    }

    // ==================================================================
    // ② 令牌本身的问题 → 401（三种，文案必须与单体一致）
    // ==================================================================

    @Test
    @DisplayName("[鉴权/会员令牌] typ=user 的令牌打后台 → 401「登录已失效，请使用管理员账号登录」（双体系隔离）")
    void memberToken_isRejectedOnAdminEndpoints() throws Exception {
        String memberToken = com.mall.product.support.AdminTokenMinter.member(jwtSecret, 1L);
        MvcResult r = mockMvc.perform(get("/api/admin/product/page")
                .header("Authorization", "Bearer " + memberToken)).andReturn();

        assertEquals(401, codeOf(body(r)), "会员令牌不得用于后台接口，body=" + body(r));
        assertEquals("登录已失效，请使用管理员账号登录", messageOf(body(r)),
                "文案必须与单体 AdminSession 逐字一致（双体系隔离的专用文案）");
    }

    @Test
    @DisplayName("[鉴权/过期] 已过期 60 秒的管理员令牌 → 401「登录已失效，请重新登录」")
    void expiredToken_isRejected() throws Exception {
        String expired = com.mall.product.support.AdminTokenMinter.expiredAdmin(jwtSecret, ADMIN_ID);
        MvcResult r = mockMvc.perform(get("/api/admin/product/page")
                .header("Authorization", "Bearer " + expired)).andReturn();

        assertEquals(401, codeOf(body(r)), "过期令牌必须 401，body=" + body(r));
        assertEquals("登录已失效，请重新登录", messageOf(body(r)));
    }

    @Test
    @DisplayName("[鉴权/错签名] 用别的密钥签的令牌 → 401（证明签名真的在校验，不是\"有令牌就放行\"）")
    void tokenSignedWithAnotherSecret_isRejected() throws Exception {
        String foreign = com.mall.product.support.AdminTokenMinter
                .withSecret("another-secret-0123456789abcdefghijklmn", ADMIN_ID);
        MvcResult r = mockMvc.perform(get("/api/admin/product/page")
                .header("Authorization", "Bearer " + foreign)).andReturn();
        assertEquals(401, codeOf(body(r)), "别家密钥签的令牌必须被拒，body=" + body(r));
        assertEquals("登录已失效，请重新登录", messageOf(body(r)));
    }

    @Test
    @DisplayName("[鉴权/篡改] 合法令牌改掉签名最后一个字符 → 401（不许\"看起来像 JWT 就放行\"）")
    void tamperedSignature_isRejected() throws Exception {
        String good = adminToken();
        char last = good.charAt(good.length() - 1);
        String tampered = good.substring(0, good.length() - 1) + (last == 'A' ? 'B' : 'A');

        MvcResult r = mockMvc.perform(get("/api/admin/product/page")
                .header("Authorization", "Bearer " + tampered)).andReturn();
        assertEquals(401, codeOf(body(r)), "篡改签名的令牌必须被拒，body=" + body(r));
        assertEquals("登录已失效，请重新登录", messageOf(body(r)));
    }

    @Test
    @DisplayName("[鉴权/typ 缺失] 令牌里没有 typ 字段 → 401「请使用管理员账号登录」（不能默认为管理员）")
    void tokenWithoutType_isRejected() throws Exception {
        // typ 传空串：正则匹配不到 ⇒ type=null ⇒ 不等于 "admin" ⇒ 拒
        String noType = com.mall.product.support.AdminTokenMinter
                .token(jwtSecret, ADMIN_ID, "admin", "", 0L, 3600L);
        MvcResult r = mockMvc.perform(get("/api/admin/product/page")
                .header("Authorization", "Bearer " + noType)).andReturn();
        assertEquals(401, codeOf(body(r)), "没有 typ 的令牌必须被拒，body=" + body(r));
        assertEquals("登录已失效，请使用管理员账号登录", messageOf(body(r)));
    }

    // ==================================================================
    // ③ 写端点：无令牌必须被拒 **且不写库**（前后各取一次证据）
    // ==================================================================

    @Test
    @Transactional
    @DisplayName("[鉴权/写端点] 无令牌 POST 建商品 → 401 且**库里一行都没多**（拒绝发生在 handler 之前）")
    void writeEndpoint_withoutToken_isRejected_andWritesNothing() throws Exception {
        long before = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu");
        long skuBefore = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_sku");

        MvcResult r = mockMvc.perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(com.mall.common.support.JsonKit.toJson(
                                minimalProductBody("匿名写入尝试-" + suffix))))
                .andExpect(status().isOk()).andReturn();

        assertEquals(401, codeOf(body(r)), "匿名写后台端点必须 401，body=" + body(r));
        assertEquals(before, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu"),
                "⚠️ 被拒的写请求不得落库：pms_spu 行数必须不变（这条比'返回 401'强得多）");
        assertEquals(skuBefore, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_sku"),
                "SKU 行数也必须不变");
    }

    @Test
    @DisplayName("[鉴权/写端点-删除] 无令牌 DELETE 商品 → 401 且目标行**仍在**")
    void deleteEndpoint_withoutToken_isRejected_andRowSurvives() throws Exception {
        // 拿一个真实存在的 spuId（**只读**，不依赖它是谁）
        Long spuId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + EXPECTED_SCHEMA + ".pms_spu ORDER BY id LIMIT 1", Long.class);
        assertTrue(spuId != null && spuId > 0, "库里应当至少有一个商品（前置条件）");

        MvcResult r = mockMvc.perform(delete("/api/admin/product/" + spuId))
                .andExpect(status().isOk()).andReturn();

        assertEquals(401, codeOf(body(r)), "匿名删除必须 401，body=" + body(r));
        assertEquals(1L, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", spuId),
                "被拒的删除不得生效：商品 " + spuId + " 必须还在");
    }

    // ==================================================================
    // ④ 对照：带**正确**管理员令牌必须成功（否则"401 全绿"可能只是端点坏了）
    // ==================================================================

    @Test
    @DisplayName("[鉴权/对照-读] 带正确管理员令牌 → code=0 且取到**真数据**（三个读端点）")
    void withValidAdminToken_readEndpointsReturnRealData() throws Exception {
        MvcResult page = perform(get("/api/admin/product/page").param("pageNum", "1").param("pageSize", "5"))
                .andExpect(status().isOk()).andReturn();
        String pageBody = body(page);
        assertEquals(0, codeOf(pageBody), "带正确令牌必须成功，body=" + pageBody);
        List<?> list = JsonPath.read(pageBody, "$.data.list");
        assertFalse(list.isEmpty(), "后台商品列表必须有数据（空列表可能是'闸放行了但端点没接上'）");
        assertTrue(((Number) JsonPath.read(pageBody, "$.data.total")).longValue() > 0, "总数应当 > 0");

        MvcResult brands = perform(get("/api/admin/brand/list")).andReturn();
        assertEquals(0, codeOf(body(brands)), "brand/list 必须成功，body=" + body(brands));
        assertFalse(((List<?>) JsonPath.read(body(brands), "$.data")).isEmpty(), "品牌列表必须有数据");

        MvcResult tree = perform(get("/api/admin/category/tree")).andReturn();
        assertEquals(0, codeOf(body(tree)), "category/tree 必须成功，body=" + body(tree));
        assertFalse(((List<?>) JsonPath.read(body(tree), "$.data")).isEmpty(), "类目树必须有数据");
    }

    @Test
    @Transactional
    @DisplayName("[鉴权/对照-写] 带正确管理员令牌 → 真的写进库（与'无令牌不写库'成对，缺一不可）")
    void withValidAdminToken_writeEndpointReallyWrites() throws Exception {
        long before = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu");
        String title = "带令牌建商品-" + suffix;

        MvcResult r = perform(post("/api/admin/product")
                .contentType(MediaType.APPLICATION_JSON)
                .content(com.mall.common.support.JsonKit.toJson(minimalProductBody(title))))
                .andExpect(status().isOk()).andReturn();

        assertEquals(0, codeOf(body(r)), "带正确令牌的写入必须成功，body=" + body(r));
        assertEquals(before + 1, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu"),
                "对照成立：有令牌时**确实**写进了库（否则'无令牌不写库'那条断言可能只是端点坏了）");
        assertEquals(1L, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE title = ?", title));
    }

    // ---------- helpers ----------

    /** 最小可用的建商品请求体（类目 12、品牌 1、一个 SKU） */
    private Map<String, Object> minimalProductBody(String title) {
        return Map.of(
                "categoryId", 12L,
                "brandId", 1L,
                "title", title,
                "subtitle", "鉴权用例",
                "mainImage", "http://img/auth/main.jpg",
                "description", "鉴权用例",
                "detailHtml", "<p>auth</p>",
                "images", List.of("http://img/auth/1.jpg"),
                "params", List.of(Map.of("name", "材质", "value", "纯棉")),
                "skus", List.of(Map.of(
                        "skuCode", "AUTH-" + suffix,
                        "specValues", List.of(Map.of("name", "颜色", "value", "黑")),
                        "price", 19900L,
                        "originalPrice", 25900L,
                        "stock", 5)));
    }

    private static int codeOf(String responseBody) {
        return ((Number) JsonPath.read(responseBody, "$.code")).intValue();
    }

    private static String messageOf(String responseBody) {
        Object m = JsonPath.read(responseBody, "$.message");
        return m == null ? null : String.valueOf(m);
    }
}
