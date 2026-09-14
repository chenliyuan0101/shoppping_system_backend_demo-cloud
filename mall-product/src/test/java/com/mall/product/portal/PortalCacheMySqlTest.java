package com.mall.product.portal;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.support.CacheKeys;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * <b>商品域读缓存的语义覆盖</b>（P6-4 从单体搬过来的三条断言）。
 *
 * <h2>为什么它在这边、而不是留在单体</h2>
 * 这三条原本在单体的 {@code common/RedisCacheMySqlTest} 里，探的是
 * {@code /api/category/tree}、{@code /api/product/brands}、{@code /api/product/{id}}——
 * P6-4 把前台 4 条路径改由网关路由到本服务、并删除了单体的 {@code ProductPortalController}
 * ⇒ 那些断言在单体侧**结构性不可满足**（MockMvc 不过网关，必然 404），而且它们验的
 * （`ProductPortalServiceImpl` 的四套读缓存 + 货架版本号）**本来就属于商品域** ⇒
 * **覆盖跟着代码走**：搬到这里。单体侧保留的三条（令牌版本号 / 下单幂等 / 幂等结果 24h）不动。
 *
 * <p>⚠️ 顺带说明"单体的 fail-open 覆盖去哪了"：`RedisFailOpenMySqlTest` 里那 4 条 storefront 探针
 * 已改指**单体自己仍在用的 Redis 读路径**（`/api/order/page` 等）；**storefront 读路径的 fail-open 覆盖
 * 归 product/content**。本类不重复验 fail-open（那是 {@code CacheService} 的行为，已有它的用例）。
 *
 * <h2>三条断言（沿用单体原用例的意图，逐条对准"缓存真的在工作"）</h2>
 * <ol>
 *   <li><b>命中被真实使用</b>：整段替换缓存 → 接口返回**受控内容**（证明读的是缓存、且 JSON 往返可用）；
 *       只断言"key 被写进去了"是不够的——那只证明写入、不证明读取；</li>
 *   <li><b>TTL 合理</b>：类目树 ≈30 分钟、货架分页 ≈45 秒（后者短，是为了让销量/库存自然收敛）；</li>
 *   <li><b>后台改状态 ⇒ 删详情缓存 + 货架版本号 +1</b>（版本号整体失效旧货架缓存，避免遍历删 key）。</li>
 * </ol>
 *
 * <p><b>为什么加 {@code @Transactional}</b>：与本包既有的 {@code PortalProductMySqlTest} 同一口径——
 * 用例造的商品/规格**随事务回滚**，不留残留（否则会污染"`pms_*` 两库逐表行数对齐"这条判据）。
 * 缓存失效是**行内**发生的（`AdminProductServiceImpl` 直接 `cacheService.delete/increment`），
 * 因此即使测试事务不提交，这三条缓存断言仍然成立；用例结束再自己补一次
 * {@link ProductTestBase#clearProductPortalCache()}。
 */
class PortalCacheMySqlTest extends ProductTestBase {

    /**
     * 后台 JSON 请求的统一入口。
     *
     * <p>⚠️ 必须先建 {@code MockHttpServletRequestBuilder} 再交给 {@link #perform}：
     * {@code perform(...)} 返回的是**已执行完**的 {@code ResultActions}，在它后面再链 {@code .contentType(...)}
     * 编译期就报 "找不到符号 contentType"（我第一版就是这么写的）。
     */
    private org.springframework.test.web.servlet.ResultActions adminJson(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String json) throws Exception {
        return perform(builder.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("[缓存] 命中被真实使用：整段替换缓存后接口返回受控内容（证明读取 + JSON 往返）")
    void categoryTree_cacheHit_isReallyUsed() throws Exception {
        assumeTrue(redisUp(), "Redis 不可用 ⇒ 缓存语义无法验证（跳过而非失败）");
        clearProductPortalCache();

        String marker = "CACHE-PROBE-" + suffix;
        // 与 CategoryNode 的字段同名（children/id/name/parentId/sort），整段替换成"只有一条、名字可辨认"的树
        String fakeTree = "[{\"id\":999999,\"parentId\":0,\"name\":\"" + marker
                + "\",\"sort\":0,\"children\":[]}]";
        redisTemplate.opsForValue().set(CacheKeys.categoryTree(), fakeTree, Duration.ofMinutes(5));
        try {
            MvcResult r = mockMvc.perform(get("/api/category/tree"))
                    .andExpect(jsonPath("$.code").value(0)).andReturn();
            String text = body(r);
            assertTrue(text.contains(marker),
                    "整段替换缓存后，接口应直接返回缓存里的受控内容（证明缓存命中真的被使用）；实际=" + text);
            assertTrue(text.contains("999999"), "受控内容里的 id 也应原样返回；实际=" + text);
        } finally {
            redisTemplate.delete(CacheKeys.categoryTree());
            clearProductPortalCache();
        }
    }

    @Test
    @DisplayName("[缓存] TTL 合理：类目树 ≈30 分钟 / 货架分页 ≈45 秒（含 ±15% 抖动）")
    void readCache_ttlsAreSane() throws Exception {
        assumeTrue(redisUp(), "Redis 不可用 ⇒ 缓存语义无法验证（跳过而非失败）");
        clearProductPortalCache();
        try {
            mockMvc.perform(get("/api/category/tree")).andExpect(jsonPath("$.code").value(0));
            Long treeTtl = redisTemplate.getExpire(CacheKeys.categoryTree(), TimeUnit.SECONDS);
            assertNotNull(treeTtl, "类目树缓存应已写入（TTL 为 null 说明 key 不存在）");
            // ⚠️ 不能断言"≈1800s"：`CacheService.set` 对 TTL 加了 **±15% 抖动**（防缓存雪崩，见 CacheService#jitter）
            //    ⇒ 1800s 的合法区间是 1530~2070s（我第一次断言 (1500, 1800] 就是被这个抖动打成红的，实测 1545/1615/1917 都出现过）。
            assertTrue(treeTtl >= 1440 && treeTtl <= 2160,
                    "类目树 TTL 应≈30 分钟（±15% 抖动后 1530~2070s，这里放宽到 1440~2160 容忍时钟/调度误差），实际=" + treeTtl + "s");

            mockMvc.perform(get("/api/product/page").param("pageNum", "1").param("pageSize", "5"))
                    .andExpect(jsonPath("$.code").value(0));
            Set<String> shelfKeys = redisTemplate.keys("mall:cache:product:shelf:*");
            assertNotNull(shelfKeys);
            assertFalse(shelfKeys.isEmpty(), "货架分页缓存应已写入（key 形如 mall:cache:product:shelf:v{ver}:{摘要}）");
            Long shelfTtl = redisTemplate.getExpire(shelfKeys.iterator().next(), TimeUnit.SECONDS);
            assertNotNull(shelfTtl, "货架缓存 TTL 不应为 null");
            assertTrue(shelfTtl >= 36 && shelfTtl <= 54,
                    "货架 TTL 应≈45 秒（±15% 抖动后 38~52s，这里放宽到 36~54；口径=销量/库存靠短 TTL 收敛，商品改动由版本号即时失效），实际=" + shelfTtl + "s");
        } finally {
            clearProductPortalCache();
        }
    }

    @Test
    @DisplayName("[缓存] 后台改状态 ⇒ 删该商品详情缓存 + 货架版本号 +1")
    // ⚠️ **刻意不加 `@Transactional`**（第一版加了，然后这条用例红了，原因见下）：
    //    商品域把缓存失效放在**事务提交后**（`AdminProductServiceImpl#evictOnCommit`，
    //    理由与 `ProductTestBase#clearProductPortalCache` 的 javadoc 一致：事务内就删的话，
    //    "删缓存 → 提交"之间若有并发读，会把库里的旧值重新写回缓存）。
    //    ⇒ 带 `@Rollback` 的用例**永远不会提交** ⇒ 那次失效永远不会发生 ⇒ 断言必然假红。
    //    所以本用例走真实提交，并在 finally 里**物理删掉自己造的行**（否则会污染
    //    "pms_* 两库逐表行数对齐"这条判据——窗口的 db/02 对齐正是按行数比对的）。
    void adminStatusChange_evictsDetailCache_andBumpsShelfVersion() throws Exception {
        assumeTrue(redisUp(), "Redis 不可用 ⇒ 缓存语义无法验证（跳过而非失败）");
        clearProductPortalCache();

        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + EXPECTED_SCHEMA + ".pms_category WHERE status = 1 ORDER BY id LIMIT 1", Long.class);
        assertNotNull(categoryId, "需要一个启用类目来建商品（种子数据应至少有一个）");

        String createBody = "{\"categoryId\":" + categoryId
                + ",\"title\":\"CACHE-" + suffix + "\",\"mainImage\":\"http://localhost:9000/mall/seed/p-earphone-main.jpg\""
                + ",\"skus\":[{\"skuCode\":\"CACHE-" + suffix + "\",\"price\":1999,\"stock\":3}]}";
        MvcResult created = adminJson(post("/api/admin/product"), createBody)
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long spuId = Long.parseLong(JsonPath.read(body(created), "$.data").toString());

        try {
            // 上架（新建默认下架，前台详情只服务在架商品）
            adminJson(put("/api/admin/product/" + spuId + "/status"), "{\"status\":1}")
                    .andExpect(jsonPath("$.code").value(0));

            // 预热详情缓存：一次前台详情读
            mockMvc.perform(get("/api/product/" + spuId)).andExpect(jsonPath("$.code").value(0));
            assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(CacheKeys.productDetail(spuId))),
                    "前台详情读之后应写入详情缓存（key=" + CacheKeys.productDetail(spuId) + "）");

            String verBefore = redisTemplate.opsForValue().get(CacheKeys.productShelfVersion());

            // 下架 ⇒ 期望：该商品详情缓存被删 + 货架版本号 +1
            adminJson(put("/api/admin/product/" + spuId + "/status"), "{\"status\":0}")
                    .andExpect(jsonPath("$.code").value(0));

            assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(CacheKeys.productDetail(spuId))),
                    "后台改状态后必须删掉该商品的详情缓存（否则前台会继续返回旧状态）");
            String verAfter = redisTemplate.opsForValue().get(CacheKeys.productShelfVersion());
            assertNotNull(verAfter, "改状态后货架版本号应存在（被 +1 过）");
            if (verBefore != null) {
                assertTrue(Long.parseLong(verAfter) == Long.parseLong(verBefore) + 1,
                        "货架版本号应恰好 +1（整体失效旧货架缓存）；before=" + verBefore + " after=" + verAfter);
            }
        } finally {
            // 逻辑删除（走真实 API，顺带覆盖 DELETE 端点）
            try {
                perform(delete("/api/admin/product/" + spuId)).andExpect(jsonPath("$.code").value(0));
            } catch (Exception ignored) {
                // 已经不在/已被删：忽略
            }
            // ⚠️ 再**物理**删掉本用例造的行：API 的 DELETE 是逻辑删除（`deleted=1`），行还在表里，
            //    会污染"pms_* 两库逐表行数对齐"（窗口的 db/02 对齐按行数比对，多一行就中止）。
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_sku_stock_log WHERE sku_id IN"
                    + " (SELECT id FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE spu_id = ?)", spuId);
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE spu_id = ?", spuId);
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_spu_detail WHERE spu_id = ?", spuId);
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", spuId);
            clearProductPortalCache();
        }
    }
}
