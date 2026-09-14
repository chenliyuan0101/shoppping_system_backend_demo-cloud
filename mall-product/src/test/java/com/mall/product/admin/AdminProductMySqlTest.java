package com.mall.product.admin;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.support.JsonKit;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 后台类目/商品/品牌 16 条端点的真库测试（从单体 {@code admin.AdminAuthProductMySqlTest} 迁来的
 * **业务部分**）。
 *
 * <h2>刻意**没有**搬过来的三条用例（及其理由）</h2>
 * 单体那三条是 {@code adminLogin_ok} / {@code adminApi_withoutToken_rejected} /
 * {@code userTokenCannotAccessAdmin}——它们断言的是**认证域**的行为：
 * {@code POST /api/admin/auth/login}（管理员登录、签发 {@code typ=admin} 的 JWT）、
 * 未登录 401、用户 token 与管理端 token 双向隔离。
 * <p>其中"登录/签发"确实**不属于商品域**（P7 的 {@code mall-admin} BFF 是它的归宿），
 * 但它后半段的**消费侧**（401 文案、双体系隔离）在 P6-1b 之后由本服务自己承担了一部分：
 * 令牌由测试本地签发（{@code support/AdminTokenMinter}），断言集中在
 * {@code AdminAuthGateMySqlTest} 与 {@link #adminEndpoints_requireAdminToken_gapClosed()}。
 * <p>⚠️ 仍留在单体的那部分：**管理员是否存在 / 是否被禁用 / 令牌版本号**（要读 {@code sys_user}
 * 与认证域的版本号）—— 见 {@code config/AdminAuthInterceptor} 类注释，别读成"鉴权已完整搬好"。
 */
@Transactional
@Rollback
class AdminProductMySqlTest extends ProductTestBase {

    private static final long SEED_CATEGORY_ID = 12L;   // 耳机音箱（parent 11 → root 1）
    private static final long SEED_BRAND_ID = 1L;       // 苹果

    // ==================================================================
    // 类目 + 商品全链路（单体 categoryAndProductFullFlow 的等价物）
    // ==================================================================

    @Test
    @DisplayName("[真库] 建类目→建商品(含2 SKU)→查询/详情/上下架/删除 全链路")
    void categoryAndProductFullFlow() throws Exception {
        // ---- 类目 ----
        String rootName = "根类_" + suffix;
        long rootId = readId(perform(post("/api/admin/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + rootName + "\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn());

        String childName = "子类_" + suffix;
        long childId = readId(perform(post("/api/admin/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + rootId + ",\"name\":\"" + childName + "\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn());

        perform(get("/api/admin/category/tree"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.id==" + rootId + ")].children[0].name").value(childName));

        // 两级限制与引用保护（类目删除的两条 409/400 文案，属于后台契约）
        perform(post("/api/admin/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + childId + ",\"name\":\"三级_" + suffix + "\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("仅支持两级类目，不能在三级下新增"));

        // ---- 商品（2 个 SKU，挂 seed 品牌 1 = 苹果）----
        String title = "测试商品_" + suffix;
        Map<String, Object> product = productBody(childId, title, 2);
        product.put("brandId", SEED_BRAND_ID);
        long spuId = readId(perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(product)))
                .andExpect(jsonPath("$.code").value(0)).andReturn());

        // 新增默认下架（改造前的行为：后台审核后才上架）
        perform(get("/api/admin/product/" + spuId))
                .andExpect(jsonPath("$.data.status").value(0));

        // 分页 + 品牌名回显 + 起售价/总库存（由 SKU 聚合而来）
        perform(get("/api/admin/product/page").param("categoryId", String.valueOf(childId)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].minPrice").value(19900))
                .andExpect(jsonPath("$.data.list[0].totalStock").value(50))
                .andExpect(jsonPath("$.data.list[0].brandId").value(SEED_BRAND_ID))
                .andExpect(jsonPath("$.data.list[0].brandName").value("苹果"));

        // 品牌筛选
        perform(get("/api/admin/product/page")
                        .param("categoryId", String.valueOf(childId)).param("brandId", "1"))
                .andExpect(jsonPath("$.data.total").value(1));
        perform(get("/api/admin/product/page")
                        .param("categoryId", String.valueOf(childId)).param("brandId", "2"))
                .andExpect(jsonPath("$.data.total").value(0));

        // 详情：images/params/specValues JSON 已还原为数组
        perform(get("/api/admin/product/" + spuId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.categoryName").value(childName))
                .andExpect(jsonPath("$.data.brandName").value("苹果"))
                .andExpect(jsonPath("$.data.images[0]").value("http://img/test/1.jpg"))
                .andExpect(jsonPath("$.data.params[0].name").value("材质"))
                .andExpect(jsonPath("$.data.skus.length()").value(2))
                .andExpect(jsonPath("$.data.skus[0].specValues[0].value").value("黑"));

        // 上架
        perform(put("/api/admin/product/" + spuId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        perform(get("/api/admin/product/" + spuId))
                .andExpect(jsonPath("$.data.status").value(1));

        // 条件筛选：类目(选一级含子类) / 状态 / 价格区间(按最低价，单位分)
        perform(get("/api/admin/product/page").param("categoryId", String.valueOf(rootId)))
                .andExpect(jsonPath("$.data.total").value(1));
        perform(get("/api/admin/product/page")
                        .param("categoryId", String.valueOf(childId)).param("status", "1"))
                .andExpect(jsonPath("$.data.total").value(1));
        // 最低价 19900 < 20000 → 被排除
        perform(get("/api/admin/product/page")
                        .param("categoryId", String.valueOf(childId)).param("minPrice", "20000"))
                .andExpect(jsonPath("$.data.total").value(0));
        // 存在 ≤20000 的 SKU → 命中
        perform(get("/api/admin/product/page")
                        .param("categoryId", String.valueOf(childId)).param("maxPrice", "20000"))
                .andExpect(jsonPath("$.data.total").value(1));
        // 状态不匹配 → 0
        perform(get("/api/admin/product/page")
                        .param("categoryId", String.valueOf(childId)).param("status", "0"))
                .andExpect(jsonPath("$.data.total").value(0));

        // 删除商品 → 再查详情 404
        perform(delete("/api/admin/product/" + spuId))
                .andExpect(jsonPath("$.code").value(0));
        perform(get("/api/admin/product/" + spuId))
                .andExpect(jsonPath("$.code").value(404));

        // 类目还有子类 → 409；先删子类再删根类目
        perform(delete("/api/admin/category/" + rootId))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("请先删除子类目"));
        perform(delete("/api/admin/category/" + childId))
                .andExpect(jsonPath("$.code").value(0));
        perform(delete("/api/admin/category/" + rootId))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================================================================
    // 主图的两条边界（从单体逐条搬：它们各对应一次真实缺陷）
    // ==================================================================

    @Test
    @DisplayName("[真库] 新建商品未传主图 → 自动用占位图(不再撞 DB NOT NULL 报 500)")
    void createProduct_withoutMainImage_usesPlaceholder() throws Exception {
        Map<String, Object> body = productBody(SEED_CATEGORY_ID, "缺主图商品_" + suffix, 1);
        body.remove("mainImage");   // 故意不传主图

        long spuId = readId(perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(body)))
                .andExpect(jsonPath("$.code").value(0)).andReturn());

        perform(get("/api/admin/product/" + spuId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mainImage").value(containsString("placeholder")));
    }

    @Test
    @DisplayName("[真库] 编辑商品传空主图 → 保留原图(不会把主图覆盖成空串)")
    void updateProduct_withBlankMainImage_keepsExisting() throws Exception {
        String original = "http://img/test/keep-me.jpg";
        Map<String, Object> body = productBody(SEED_CATEGORY_ID, "主图保留商品_" + suffix, 1);
        body.put("mainImage", original);

        long spuId = readId(perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(body)))
                .andExpect(jsonPath("$.code").value(0)).andReturn());

        Map<String, Object> update = productBody(SEED_CATEGORY_ID, "主图保留商品_" + suffix, 1);
        update.put("mainImage", "");   // 空串 = 不修改主图
        perform(put("/api/admin/product/" + spuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(update)))
                .andExpect(jsonPath("$.code").value(0));

        perform(get("/api/admin/product/" + spuId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mainImage").value(original));
    }

    // ==================================================================
    // 品牌端点（AdminBrandController 的 5 条）
    // ==================================================================

    @Test
    @DisplayName("[真库] 品牌 page/list/建/改/删 5 条端点")
    void brandEndpoints_fullFlow() throws Exception {
        String name = "品牌_" + suffix;
        long brandId = readId(perform(post("/api/admin/brand")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sort\":3,\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn());

        // page（关键字 + 状态）
        perform(get("/api/admin/brand/page").param("keyword", name))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(brandId));
        perform(get("/api/admin/brand/page").param("keyword", name).param("status", "0"))
                .andExpect(jsonPath("$.data.total").value(0));

        // list（仅启用，下拉用）
        perform(get("/api/admin/brand/list"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.id==" + brandId + ")].name").value(name));

        // 改名（会触发"品牌下在架商品重写索引"的标记；P6-1 落 Redis 兜底集合，不改 ES）
        perform(put("/api/admin/brand/" + brandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "_改\"}"))
                .andExpect(jsonPath("$.code").value(0));
        perform(get("/api/admin/brand/page").param("keyword", name + "_改"))
                .andExpect(jsonPath("$.data.total").value(1));

        // 品牌下有商品 → 409；先把商品删掉再删品牌
        long spuId = readId(perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(withBrand(productBody(SEED_CATEGORY_ID, "品牌引用_" + suffix, 1), brandId))))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
        perform(delete("/api/admin/brand/" + brandId))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该品牌下存在商品，请先移除商品再删除"));
        perform(delete("/api/admin/product/" + spuId))
                .andExpect(jsonPath("$.code").value(0));
        perform(delete("/api/admin/brand/" + brandId))
                .andExpect(jsonPath("$.code").value(0));
        perform(get("/api/admin/brand/page").param("keyword", name + "_改"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("[真库] 商品校验：SKU 编码重复 / 价格<=0 / 库存为负 / 类目不存在 都是 400")
    void productValidation_messages() throws Exception {
        Map<String, Object> dup = productBody(SEED_CATEGORY_ID, "重复编码_" + suffix, 1);
        List<Map<String, Object>> skus = new ArrayList<>();
        skus.add(Map.of("skuCode", "DUP-" + suffix, "price", 100L, "stock", 1));
        skus.add(Map.of("skuCode", "DUP-" + suffix, "price", 200L, "stock", 1));
        dup.put("skus", skus);
        perform(post("/api/admin/product").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(dup)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("SKU 编码不能重复"));

        Map<String, Object> badPrice = productBody(SEED_CATEGORY_ID, "零价_" + suffix, 1);
        badPrice.put("skus", List.of(Map.of("skuCode", "Z-" + suffix, "price", 0L, "stock", 1)));
        perform(post("/api/admin/product").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(badPrice)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("SKU 价格必须大于 0"));

        Map<String, Object> badStock = productBody(SEED_CATEGORY_ID, "负库存_" + suffix, 1);
        badStock.put("skus", List.of(Map.of("skuCode", "N-" + suffix, "price", 100L, "stock", -1)));
        perform(post("/api/admin/product").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(badStock)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("SKU 库存不能为负"));

        Map<String, Object> badCategory = productBody(9_999_999L, "坏类目_" + suffix, 1);
        perform(post("/api/admin/product").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(badCategory)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请选择有效的商品类目"));
    }

    /**
     * <b>原"已知缺口"用例，P6-1b 之后翻转为"缺口已闭合"</b>。
     *
     * <p>历史：P6-1 时本服务对 {@code /api/admin/**} **不做任何自己的鉴权**（单体那套
     * {@code AdminAuthInterceptor} 属认证域，当时没搬过来），于是这条用例把"匿名可调"钉在测试里
     * ——谁补上闸门它就会红，逼人回来更新记录（而不是留下"看起来有鉴权其实没有"的错觉）。
     *
     * <p>P6-1b 装了闸（{@code config/AdminAuthInterceptor}：HS256 签名 + 有效期 + {@code typ=admin}），
     * 所以它**如期变红**，现在改写为它的反面：匿名调用必须被拒、且**不产生任何副作用**。
     * 更细的鉴权矩阵（会员令牌/过期/错签名/篡改/畸形头、读写端点）在
     * {@code AdminAuthGateMySqlTest} 里 —— 本用例只留最粗的一条"闸门存在"。
     *
     * <p>⚠️ 仍然**不是完整鉴权**：管理员是否存在 / 是否被禁用 / 令牌版本号三项要读 {@code sys_user}
     * 与认证域的版本号，本服务做不到，仍由单体保留、P7 移交 {@code mall-admin}。
     * 见 {@code config/AdminAuthInterceptor} 的类注释。
     */
    @Test
    @DisplayName("[缺口已闭合] /api/admin/** 匿名调用 401 且不写库（P6-1b 之前这里会成功并真的落库）")
    void adminEndpoints_requireAdminToken_gapClosed() throws Exception {
        // 读端点：匿名（**直接** mockMvc.perform，不走基类那个会自动带令牌的 perform 包装）
        for (String path : List.of("/api/admin/product/page", "/api/admin/brand/list",
                "/api/admin/category/tree")) {
            mockMvc.perform(get(path))
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.message").value("未登录"));
        }

        // 写端点：匿名建商品必须被拒，且**库里一行都没多**（副作用为零才是"闸门真的拦住了"）
        String title = "无鉴权写_" + suffix;
        long before = countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu");
        mockMvc.perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(productBody(SEED_CATEGORY_ID, title, 1))))
                .andExpect(jsonPath("$.code").value(401));
        assertEquals(before, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu"),
                "被拒的写请求不得落库：pms_spu 行数必须不变");
        assertEquals(0L, countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE title = ?", title),
                "匿名尝试写的那个标题不得出现在库里");
    }

    // ---------- helpers ----------

    private Map<String, Object> withBrand(Map<String, Object> body, long brandId) {
        body.put("brandId", brandId);
        return body;
    }

    private Map<String, Object> productBody(long categoryId, String title, int skuCount) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("categoryId", categoryId);
        product.put("title", title);
        product.put("subtitle", "接口自动创建");
        product.put("mainImage", "http://img/test/main.jpg");
        product.put("description", "自动化测试商品");
        product.put("detailHtml", "<p>detail</p>");
        product.put("images", List.of("http://img/test/1.jpg", "http://img/test/2.jpg"));
        product.put("params", List.of(Map.of("name", "材质", "value", "纯棉")));
        List<Map<String, Object>> skus = new ArrayList<>();
        for (int i = 0; i < skuCount; i++) {
            Map<String, Object> sku = new LinkedHashMap<>();
            sku.put("skuCode", "T-" + suffix + "-" + i);
            sku.put("specValues", List.of(Map.of("name", "颜色", "value", i == 0 ? "黑" : "白")));
            sku.put("price", i == 0 ? 19900L : 20900L);
            sku.put("originalPrice", 25900L);
            sku.put("stock", 25);
            skus.add(sku);
        }
        product.put("skus", skus);
        return product;
    }

    private static long readId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.data");
        return id.longValue();
    }
}
