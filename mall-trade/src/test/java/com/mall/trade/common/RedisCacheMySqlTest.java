package com.mall.trade.common;

import com.jayway.jsonpath.JsonPath;
import com.mall.trade.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.mall.common.support.MemberId;

/**
 * Redis 集成测试(真库 + 真 Redis)：单体自己**仍在用**的那部分 Redis 语义 —— 令牌版本与下单幂等。
 *
 * <p>⚠️ <b>P6-4：三条"商品域读缓存"用例已搬走</b>（原 {@code cacheHit_servesRedisContent} /
 * {@code readCache_written} / {@code productDetailCache_andShelfVersionInvalidation}）。
 * 原因：它们探的是 {@code /api/category/tree}、{@code /api/product/brands}、{@code /api/product/{id}}，
 * 而 P6-4(D7) 把这 4 条前台路径改由网关路由到 {@code mall-product}、并删除了单体的
 * {@code ProductPortalController} ⇒ 在单体里用 MockMvc 打它们**结构性不可满足**（不过网关，必然 404）；
 * 而且它们验的（{@code ProductPortalServiceImpl} 的四套读缓存 + 货架版本号）**本来就属商品域**。
 * ⇒ <b>覆盖跟着代码走</b>：等价的三条断言现在在 {@code mall-product} 的
 * {@code portal/PortalCacheMySqlTest}（缓存命中被真实使用 / TTL 含 ±15% 抖动区间 / 改状态删详情缓存+版本+1）。
 * <b>不是删掉、是搬家</b>；本类保留的 3 条（令牌版本 / 下单幂等 / 幂等键 TTL）一行未改。
 *
 * 前置：本机 Redis 未启动时整类跳过(assumeTrue)——因为 Redis 不可用时系统按 fail-open 降级，
 * 此时本类断言的对象(版本号 / 幂等键)本就不存在。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedisCacheMySqlTest extends MySqlTestBase {

    @BeforeEach
    void requireRedis() {
        assumeTrue(redisUp(), "Redis 未启动，跳过 Redis 集成测试");
    }

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    // ==================== 令牌版本(主动失效) ====================

    /**
     * 令牌版本的主动失效机制：版本号 +1 → 旧 token 立即 401。
     *
     * <p>P3-4：原来这条断言靠 {@code /api/auth/logout} 触发（登出在单体里），
     * 现在登出/改密都在 user-center，单体侧只保留<b>校验</b>这一半。
     * 因此这里直接调 {@link TokenVersionService#bump}（正是登出/改密/禁用内部做的事），
     * 断言仍然是两件真实的事：Redis 键 {@code mall:token:ver:user:{id}} 被 +1，
     * 且旧 token 打单体自己的 {@code @MemberId} 接口立刻 401。
     */
    @Test
    @DisplayName("[Redis] 令牌版本号 +1（登出/改密/禁用的统一机制）→ 旧 token 立即 401")
    void tokenVersionBump_invalidatesOldToken() throws Exception {
        String token = registerAs("rdlogout_");
        String auth = "Bearer " + token;

        mockMvc.perform(get("/api/order/page").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0));

        // 相对断言：注册时若已存在历史残留的版本键(见 tokenVersion 注释)，起点就不是 0
        String verKey = "mall:token:ver:user:" + memberId;
        long before = tokenVersion(verKey);

        tokenVersionService.bump(TokenVersionService.TYPE_USER, memberId);

        assertThat(tokenVersion(verKey)).as("版本号 +1 应写进 Redis（登出/改密/禁用共用这一个键）")
                .isEqualTo(before + 1);
        mockMvc.perform(get("/api/order/page").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(401));
        // 新签发的 token（版本号已同步）仍可用 —— 等价于"重新登录后拿到的新令牌"
        mockMvc.perform(get("/api/order/page")
                        .header("Authorization", "Bearer " + mintTokenForCurrentMember()))
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 读取令牌版本（键不存在按 0 处理）。
     *
     * <p>⚠️ 不能用"绝对值"断言：数据库重建后 `ums_member` 的自增会从头开始，
     * 而 Redis 里可能还留着**重建前**那次运行时的版本键（同 id 但会员早已不存在）。
     * 这类残留键在功能上无害（注册/登录以当前值为准，签发与校验始终自洽），
     * 但会让"版本号必须是 1"这种硬编码断言随机失败。要彻底干净就清一次
     * `mall:token:ver:user:*`（见《后端Redis使用手册.md》）。
     */
    private long tokenVersion(String key) {
        String v = redisTemplate.opsForValue().get(key);
        return v == null ? 0L : Long.parseLong(v);
    }

    // ==================== 下单幂等 ====================

    @Test
    @DisplayName("[Redis] 下单带同一 Idempotency-Key 重复提交 → 返回同一订单号，只落一单")
    void orderCreate_idempotent() throws Exception {
        String token = registerAs("rdidem_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(2001);
        int base = stock(2001);

        String idemKey = UUID.randomUUID().toString();
        String body = "{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                + ",\"buyNow\":{\"skuId\":2001,\"quantity\":1}}";

        String firstOrderNo = JsonPath.read(mockMvc.perform(post("/api/order/create")
                        .header("Authorization", auth).header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andReturn()
                .getResponse().getContentAsString(), "$.data");

        // 重复提交(同 key)：回放首次结果，不重复扣库存
        String secondOrderNo = JsonPath.read(mockMvc.perform(post("/api/order/create")
                        .header("Authorization", auth).header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andReturn()
                .getResponse().getContentAsString(), "$.data");
        assertThat(secondOrderNo).isEqualTo(firstOrderNo);

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oms_order WHERE member_id = ?", Integer.class, memberId);
        assertThat(orders).as("同一幂等键只应落一单").isEqualTo(1);
        assertStock(2001, base - 1);

        // 换一个幂等键 → 正常再下一单
        String thirdOrderNo = JsonPath.read(mockMvc.perform(post("/api/order/create")
                        .header("Authorization", auth).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andReturn()
                .getResponse().getContentAsString(), "$.data");
        assertThat(thirdOrderNo).isNotEqualTo(firstOrderNo);

        orderNos.add(firstOrderNo);
        orderNos.add(thirdOrderNo);
    }

    @Test
    @DisplayName("[Redis] 幂等结果保留 24 小时、执行锁自动释放")
    void idempotencyKeys_ttlAndLockRelease() throws Exception {
        String token = registerAs("rdttl_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(2001);

        String idemKey = UUID.randomUUID().toString();
        MvcResult created = mockMvc.perform(post("/api/order/create")
                        .header("Authorization", auth).header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                                + ",\"buyNow\":{\"skuId\":2001,\"quantity\":1}}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        // 必须登记订单号：否则 cleanup 只删会员不删订单，会留下孤儿订单(且被超时任务回补库存造成漂移)
        trackOrder(created);

        String resultKey = "mall:idem:order:result:" + memberId + ":" + idemKey;
        String lockKey = "mall:idem:order:lock:" + memberId + ":" + idemKey;
        // 结果回放 24 小时(CacheService 会做 ±15% TTL 抖动，故按区间断言)
        assertThat(redisTemplate.getExpire(resultKey))
                .isBetween(Duration.ofHours(20).toSeconds(), Duration.ofHours(28).toSeconds());
        assertThat(hasKey(lockKey)).as("执行完成后应释放执行锁").isFalse();
    }

    // ==================== helpers ====================
    // redisUp() / hasKey() / redisTemplate 由基类 MySqlTestBase 提供(各套件不再各写一份)
}
