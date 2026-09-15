package com.mall.product.portal;

import com.jayway.jsonpath.JsonPath;
import com.mall.common.support.JsonKit;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前台商品浏览 + 后台品牌/商品全链路真库测试（从单体 {@code pms.PortalBrandMySqlTest} 迁来）。
 *
 * <p>覆盖：后台建品牌 → 建商品挂品牌 → 上架 → 前台（品牌下拉 / 关键字货架 / 品牌筛选 / 详情）
 * → 下架后前台消失。
 *
 * <h2>与单体的三处差别（都是刻意的，不是漏搬）</h2>
 * <ol>
 *   <li>**删掉了 {@code productSearchService.syncProduct(spuId)} 这两次显式调用**：
 *       单体那两句是为了在"不提交事务的用例里"手动补一次 ES 同步（真同步挂在 afterCommit）。
 *       本服务 P6-1 **没有 ES 客户端**（属 P6-2），{@code ProductSearchService} 只剩 4 个方法，
 *       没有 syncProduct 的**业务**用法；索引标记改为写 Redis 兜底集合
 *       （{@code SearchUnavailableProductSearchService} —— P6-1 的"索引不可用"降级实现，
 *       它的 {@code syncProduct} 是安全空实现，不会真的写索引）。</li>
 *   <li>**清缓存改成只清商品域自己的 key**（{@code mall:cache:home:index} / {@code category:tree} /
 *       {@code brand:list} / {@code product:detail:*} / {@code product:shelf:v*} / {@code product:ver}）
 *       而不是 {@code mall:cache:*} 通配：这个 Redis 是全站共用的，
 *       实测单体的通配写法会顺手清掉会员状态缓存（{@code mall:cache:member:status:*}）。
 *       ⚠️ P4/P5 的同类用例一直用的是通配写法——这里收窄了一个已知副作用，行为等价（缓存都是可丢的）。</li>
 *   <li>**关键字检索走的是 MySQL LIKE 路径**（不是 ES）：本服务 {@code search()} 声明不可用 →
 *       {@code ProductPortalServiceImpl.searchByEs} 捕获异常后回落 MySQL——
 *       所以这个用例同时是"**检索降级**"的活体证据（P6-plan §三 第 3 条要求的降级）。
 *       它**不能**证明 ES 路径对（那是 P6-2 的事），断言里说清了这一点。</li>
 * </ol>
 */
@Transactional
@Rollback
class PortalProductMySqlTest extends ProductTestBase {

    /** 后台建品牌 → 返回 id */
    private long createBrand(String name) throws Exception {
        MvcResult r = perform(post("/api/admin/brand")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sort\":1,\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return readLong(r);
    }

    /** 后台建商品（含 1 个 SKU）→ 返回 spuId */
    private long createProduct(long categoryId, Long brandId, String title, long price, int stock) throws Exception {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("categoryId", categoryId);
        if (brandId != null) {
            product.put("brandId", brandId);
        }
        product.put("title", title);
        product.put("mainImage", "http://img/main.jpg");
        product.put("skus", List.of(Map.of(
                "skuCode", "P-" + suffix,
                "specValues", List.of(Map.of("name", "颜色", "value", "黑")),
                "price", price,
                "stock", stock)));
        MvcResult r = perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(product)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return readLong(r);
    }

    @Test
    @DisplayName("[真库] 建品牌→商品挂品牌→上架→前台可见(关键字/品牌/详情)→下架后前台消失")
    void brandAndPortalFullFlow() throws Exception {
        String brandName = "测试品牌_" + suffix;
        String title = "门户测试耳机_" + suffix;
        long brandId = createBrand(brandName);
        // seed 子类目 12 = 耳机音箱（parent 11 → root 1）
        long spuId = createProduct(12L, brandId, title, 15000L, 20);

        // 上架
        perform(put("/api/admin/product/" + spuId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));

        clearProductPortalCache();

        // 前台：品牌下拉包含新品牌
        perform(get("/api/product/brands"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.id==" + brandId + ")].name").value(brandName));

        // 前台：关键字命中（**走 MySQL LIKE 降级路径**，见类注释第 3 条）+ 品牌名/起售价/总库存
        perform(get("/api/product/page").param("keyword", title))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].brandId").value(brandId))
                .andExpect(jsonPath("$.data.list[0].brandName").value(brandName))
                .andExpect(jsonPath("$.data.list[0].minPrice").value(15000))
                .andExpect(jsonPath("$.data.list[0].totalStock").value(20));

        // 前台：按品牌过滤
        perform(get("/api/product/page").param("brandId", String.valueOf(brandId)))
                .andExpect(jsonPath("$.data.total").value(1));

        // 前台：详情可见 SKU 价格
        perform(get("/api/product/" + spuId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.brandName").value(brandName))
                .andExpect(jsonPath("$.data.skus[0].price").value(15000));

        // 下架 → 前台列表消失、详情 404
        perform(put("/api/admin/product/" + spuId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        clearProductPortalCache();
        perform(get("/api/product/page").param("brandId", String.valueOf(brandId)))
                .andExpect(jsonPath("$.data.total").value(0));
        perform(get("/api/product/" + spuId))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("[真库] 前台类目树：仅启用类目 + 真的是树（自己造一个停用根类目来证明，不靠种子数据）")
    void categoryTree_isTreeOfEnabledOnly() throws Exception {
        // ⚠️ 这条用例的第一版是**条件断言**（"如果库里存在停用类目，才断言它不在前台树里"），
        //    实测种子库里 `status=0` 的类目**恰好是 0 个**（`SELECT COUNT(*) ... WHERE status=0` = 0）
        //    ⇒ 那个 if 分支**永远不会执行**，这条断言等于没写（"看起来在测、其实没测"）。
        //    现在改成**自己造数据**：插一个停用根类目 + 一个停用子类目，再断言它们不出现在前台树里。
        //    本套件是 @Transactional + @Rollback，插入的数据随用例回滚，不会污染新库。
        String disabledRoot = "停用根类_" + suffix;
        String disabledChild = "停用子类_" + suffix;
        jdbcTemplate.update("INSERT INTO " + EXPECTED_SCHEMA
                + ".pms_category (parent_id, name, sort, status, create_time, update_time)"
                + " VALUES (0, ?, 99, 0, NOW(), NOW())", disabledRoot);
        Long disabledRootId = jdbcTemplate.queryForObject("SELECT id FROM " + EXPECTED_SCHEMA
                + ".pms_category WHERE name = ?", Long.class, disabledRoot);
        jdbcTemplate.update("INSERT INTO " + EXPECTED_SCHEMA
                + ".pms_category (parent_id, name, sort, status, create_time, update_time)"
                + " VALUES (?, ?, 1, 0, NOW(), NOW())", disabledRootId, disabledChild);
        // 前置条件断言（防止夹具自己没生效，导致下面的"不在树里"变成假绿）
        // ⚠️ 这里被我写错过一次并真的报红：我插了**根 + 子**共 2 个停用类目，却断言"恰好 1 个"。
        //    第二次独立断言（夹具前置条件）当场把这个算错的数字抓了出来——
        //    如果没有它，下面"停用类目不在树里"会用错误的夹具静默通过。
        assertEquals(1L, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_category WHERE parent_id = 0 AND status = 0"), "夹具：应恰好有一个停用根类目");
        assertEquals(2L, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_category WHERE status = 0"), "夹具：应恰好有两个停用类目（我插的根 + 子）");
        assertEquals(0L, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_category WHERE status = 0 AND name NOT IN (?, ?)", disabledRoot, disabledChild),
                "夹具：库里原本**没有**停用类目（否则本用例的'仅启用'断言会与别人的数据混淆）");

        clearProductPortalCache();
        MvcResult r = perform(get("/api/category/tree"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String b = body(r);
        List<Map<String, Object>> roots = JsonPath.read(b, "$.data");
        assertTrue(roots.size() > 0, "启用类目树不应为空（种子数据有启用类目）");
        // 树形：至少有一个根带 children
        boolean hasChildren = roots.stream().anyMatch(n -> n.get("children") instanceof List<?> kids && !kids.isEmpty());
        assertTrue(hasChildren, "类目树必须真的嵌了 children（不是平铺列表）");
        // ① 前台树的根数必须**恰好等于库里启用根类目数**（我造的停用根不在其中）
        assertEquals((int) countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_category WHERE parent_id = 0 AND status = 1"), roots.size(),
                "前台树只能有启用的根类目（停用的那个不该出现）");
        // ② 直接按名字断言"停用类目不在响应里"（比数数更直接，且与 ① 互为独立佐证）
        assertFalse(roots.stream().anyMatch(n -> disabledRoot.equals(n.get("name"))),
                "停用根类目 '" + disabledRoot + "' 不得出现在前台类目树里");
        assertFalse(b.contains(disabledRoot), "响应体里不得出现停用根类目的名字");
        assertFalse(b.contains(disabledChild), "响应体里不得出现停用子类目的名字");
    }

    private static long readLong(MvcResult result) throws Exception {
        Number n = JsonPath.read(result.getResponse().getContentAsString(), "$.data");
        return n.longValue();
    }
}
