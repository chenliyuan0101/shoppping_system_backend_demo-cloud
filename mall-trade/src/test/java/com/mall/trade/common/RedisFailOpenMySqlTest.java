package com.mall.trade.common;

import com.jayway.jsonpath.JsonPath;
import com.mall.trade.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.mall.common.support.MemberId;

/**
 * Redis **故障降级(fail-open)** 测试：把 spring.data.redis.port 指向一个没有服务的端口，
 * 模拟"Redis 挂了/没装"，验证系统退化为纯 DB 逻辑、核心链路不受影响：
 *
 *  1. 读缓存 → 回落 DB(接口照常返回数据)
 *  2. 令牌版本 → 放行(登出/改密后旧 token 不会因为拿不到版本号而报错，也不会把用户全部踢下线)
 *  3. 限流 → 放行(Redis 不可用时不误伤正常用户)，这里刻意把开关打开、阈值压到 1 来验证
 *  4. 幂等 → 放行(下单可用性优先，同 key 会重复落单——这是明确的取舍，生产应保证 Redis 高可用)
 */
@SpringBootTest(properties = {
        "spring.data.redis.port=6399",          // 无服务端口
        "spring.data.redis.timeout=500ms",
        "spring.data.redis.connect-timeout=500ms",
        "mall.cache.enabled=true",
        "mall.rate-limit.enabled=true",
        "mall.rate-limit.limit-override=1"
})
@AutoConfigureMockMvc
class RedisFailOpenMySqlTest extends MySqlTestBase {

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    @Test
    @DisplayName("[Redis 降级] Redis 不可用：交易侧读接口照常工作（单体自己的 Redis 读路径）")
    void readApis_workWithoutRedis() throws Exception {
        // ⚠️ **P6-4（D7）改指**——原来这里探的 4 条前台路径（`/api/category/tree`、`/api/product/brands`、
        //    `/api/product/page`、`/api/product/{id}`）已随 `ProductPortalController` 一并删除，改由**网关**路由到
        //    `mall-product`；MockMvc **不过网关** ⇒ 直打它们必然是 404，属**结构性不可满足**（不是环境问题）。
        //    ⇒ **覆盖跟着代码走**：
        //      · 商品域那四套读缓存（categoryTree/brandList/shelf/detail）的 fail-open 覆盖**归 mall-product**
        //        （`PortalCacheMySqlTest`，本批新增）；
        //      · 单体这里改为覆盖**它自己仍在用的 Redis 读路径**——下单/订单列表要读**令牌版本**与**会员状态缓存**，
        //        Redis 不可用时这些读必须 fail-open（否则"Redis 挂了"会直接变成"看不了自己的订单"）。
        //    ⚠️ 不许为了让它绿把前台路径加回单体（那是倒退，会把"第二条前台读路径"重新引进来）。
        String token = registerAs("rof_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(2001);
        String orderNo = buyNow(token, addressId, 2001, 1);

        // 读 1：订单列表（成员身份 → 令牌版本/会员状态缓存 → 库）
        mockMvc.perform(get("/api/order/page").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isNotEmpty());

        // 读 2：订单详情（同上；顺带证明"下单后立刻能读回自己那单"在 Redis 挂掉时仍成立）
        mockMvc.perform(get("/api/order/" + orderNo).header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));
    }

    @Test
    @DisplayName("[Redis 降级] Redis 不可用：登录态照常(版本校验放行，不误踢用户)")
    void auth_worksWithoutRedis() throws Exception {
        String token = registerAs("nor_");
        String auth = "Bearer " + token;

        mockMvc.perform(get("/api/order/page").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0));

        // "主动失效"(登出/改密/禁用)写不进 Redis：bump 静默失败，接口不报错
        tokenVersionService.bump(TokenVersionService.TYPE_USER, memberId);

        // 降级语义：旧 token 仍可用(拿不到版本号就无法做主动失效)，但不影响业务可用性
        mockMvc.perform(get("/api/order/page").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("[Redis 降级] Redis 不可用：限流放行(阈值 1 也不会把正常请求挡成 429)")
    void rateLimit_failsOpenWithoutRedis() throws Exception {
        // P8-2a：原来打的是单体自己的 /api/admin/auth/login（已被删除：管理端登录属 mall-admin）。
        // 现在改用单体仍在服务的 POST /api/upload（scope=upload，by=USER）——
        // 三次都应是"空文件被本地挡掉"(400)，而不是 429（计数写不进 Redis ⇒ fail-open 放行）。
        String token = registerAs("rof_rl_");
        MockMultipartFile empty = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(multipart("/api/upload")
                            .header("Authorization", "Bearer " + token).file(empty))
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    @DisplayName("[Redis 降级] Redis 不可用：下单照常(幂等放行 → 同 key 会重复落单)")
    void orderCreate_worksWithoutRedis() throws Exception {
        String token = registerAs("noo_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(2001);

        String idemKey = "no-redis-key";
        String body = "{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                + ",\"buyNow\":{\"skuId\":2001,\"quantity\":1}}";

        String first = JsonPath.read(mockMvc.perform(post("/api/order/create")
                        .header("Authorization", auth).header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andReturn()
                .getResponse().getContentAsString(), "$.data");
        String second = JsonPath.read(mockMvc.perform(post("/api/order/create")
                        .header("Authorization", auth).header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andReturn()
                .getResponse().getContentAsString(), "$.data");

        // 无 Redis ⇒ 无幂等记录：两次都是新单(取舍：可用性优先；生产需 Redis 高可用)
        assertThat(second).isNotEqualTo(first);
        orderNos.add(first);
        orderNos.add(second);
    }
}
