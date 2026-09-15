package com.mall.trade.auth.support;

import com.mall.trade.common.CacheKeys;
import com.mall.trade.common.TokenVersionService;
import com.mall.trade.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.mall.common.support.MemberId;

/**
 * P3-2 的核心测试：**网关身份透传**在单体侧是否被正确信任（且只被正确信任）。
 *
 * <p>这里不断言"过滤器怎么写的"，而是断言对外行为：
 * <ol>
 *   <li>带可信网关凭据 + 会员 id（**不带 Authorization**）→ 需要登录的接口正常工作
 *       （这是登录态改造后生产环境的常态路径）；</li>
 *   <li>凭据不对（客户端手写 {@code X-Member-Id}）→ **不被信任**，回落到自行验签 → 无 token 即 401
 *       （这一条是安全底线：写错这里就是任意越权）；</li>
 *   <li>令牌版本不一致 → 401「登录已失效，请重新登录」；</li>
 *   <li>会员被禁用 → 401「账号已被禁用」（即使版本号是对的也拦住）；</li>
 *   <li>会员压根不存在 → 401（快路径也必须回源确认会员还在，不能只信一个数字）；</li>
 *   <li>快路径会回填成员状态缓存，下一次请求不再回源。</li>
 * </ol>
 *
 * <p>注意 {@code Authorization} 头故意不带：这正是要证明的——身份来自网关，而不是"服务自己又验了一遍令牌"。
 *
 * <p><b>P3-4 的改动</b>：本套件原来打的是 {@code /api/auth/me|logout} 与 {@code /api/cart/count}，
 * 这些接口随会员域一起搬去了 user-center，单体侧已经不存在。
 * 现在改打单体仍然拥有的 {@code @MemberId} 接口（{@code /api/order/page}）——
 * 要验证的是<b>单体自己的身份解析</b>，与具体业务接口无关；
 * "登出后旧 token 失效"改成等价断言"令牌版本 +1 后旧 token 失效"（登出在 user-center，效果就是版本 +1）。
 *
 * <p><b>P4 批次 3 的改动</b>：{@code /api/comment/mine} 这条断言<b>搬去了评价服务</b>
 * （{@code mall-review} 的 {@code CommentApiMySqlTest#submit_andMine_requiresGatewayIdentity}）——
 * 评价的 HTTP 面已经不在单体里，继续在这里断它只会证明"单体对一个不存在的路径返回 404"。
 * 本套件因此只保留单体自己的接口；review 侧那条用同样的两个断言
 * （{@code code=401} + {@code message=未登录}）覆盖同一件事。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "mall.internal.token=test-internal-token",
        "mall.gateway.auth-token=test-gateway-token"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class GatewayIdentityMySqlTest extends MySqlTestBase {

    private static final String GW_AUTH = "X-Gateway-Auth";
    private static final String GW_MEMBER_ID = "X-Member-Id";
    private static final String GW_MEMBER_VER = "X-Member-Ver";
    private static final String GW_SECRET = "test-gateway-token";

    @Autowired
    private TokenVersionService tokenVersionService;

    @AfterEach
    void cleanUp() {
        if (memberId > 0) {
            redisTemplate.delete(CacheKeys.memberStatus(memberId));
        }
        cleanUpBaselines();
    }

    @Test
    @DisplayName("[P3-2] 回退路径回归：没有网关身份、也没有 token → 401「未登录」（全站 401 场景）")
    void anonymousStillRejected() throws Exception {
        mockMvc.perform(get("/api/order/page"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
        // 评价接口的同一断言见 mall-review/CommentApiMySqlTest（P4 批次 3 把评价的 HTTP 面搬走了）
    }

    @Test
    @DisplayName("[P3-2] 令牌版本 +1（登出/改密的等价效果）后旧 token 立即失效，走回退路径")
    void staleTokenAfterVersionBumpRejected() throws Exception {
        String token = registerAs("gwout_");
        mockMvc.perform(get("/api/order/page").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));

        // 登出在 user-center（/api/auth/logout），单体这边看到的效果就是版本号 +1
        tokenVersionService.bump(TokenVersionService.TYPE_USER, memberId);

        mockMvc.perform(get("/api/order/page").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    @DisplayName("[P3-2] 网关注入身份 → 无 Authorization 也能访问需登录接口，并回填成员缓存")
    void gatewayIdentityIsTrusted() throws Exception {
        registerAs("gw_");   // 真实造一个会员（token 不用，只用它的 id）

        mockMvc.perform(get("/api/order/page")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_MEMBER_ID, String.valueOf(memberId))
                        .header(GW_MEMBER_VER, "0"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(hasKey(CacheKeys.memberStatus(memberId)))
                .as("快路径应当把成员状态回填进缓存，后续请求即可不再回源").isTrue();
    }

    @Test
    @DisplayName("[P3-2][安全] 快路径也必须确认会员存在：不存在的会员 id → 401（不是「头里有数字就放行」）")
    void gatewayIdentityForUnknownMemberRejected() throws Exception {
        long ghostId = 999_999_998L;
        redisTemplate.delete(CacheKeys.memberStatus(ghostId));

        mockMvc.perform(get("/api/order/page")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_MEMBER_ID, String.valueOf(ghostId))
                        .header(GW_MEMBER_VER, "0"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    @DisplayName("[P3-2] 令牌版本不一致 → 401「登录已失效，请重新登录」")
    void staleVersionRejected() throws Exception {
        registerAs("gwver_");
        // 模拟"登出/改密/被禁用"之后：版本号 +1，而请求里带的还是旧版本
        tokenVersionService.bump(TokenVersionService.TYPE_USER, memberId);

        mockMvc.perform(get("/api/order/page")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_MEMBER_ID, String.valueOf(memberId))
                        .header(GW_MEMBER_VER, "0"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    @DisplayName("[P3-2][安全] 凭据不对 → 身份头不被信任，回落验签（无 token 即 401「未登录」）")
    void forgedGatewayHeadersAreRejected() throws Exception {
        registerAs("gwfake_");

        mockMvc.perform(get("/api/order/page")
                        .header(GW_AUTH, "definitely-wrong")
                        .header(GW_MEMBER_ID, String.valueOf(memberId))
                        .header(GW_MEMBER_VER, "0"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        // 完全不带头（客户端只写 X-Member-Id）同样不认
        mockMvc.perform(get("/api/order/page").header(GW_MEMBER_ID, String.valueOf(memberId)))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("[P3-2] 会员被禁用 → 401「账号已被禁用」（版本号正确也拦住）")
    void disabledMemberRejected() throws Exception {
        registerAs("gwdis_");
        // 直接改属主库并清缓存，模拟"刚被管理员禁用"；版本号故意用当前值，走状态分支
        jdbcTemplate.update("UPDATE mall_user.ums_member SET status = 0 WHERE id = ?", memberId);
        redisTemplate.delete(CacheKeys.memberStatus(memberId));
        long ver = tokenVersionService.current(TokenVersionService.TYPE_USER, memberId);

        mockMvc.perform(get("/api/order/page")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_MEMBER_ID, String.valueOf(memberId))
                        .header(GW_MEMBER_VER, String.valueOf(ver)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }
}
