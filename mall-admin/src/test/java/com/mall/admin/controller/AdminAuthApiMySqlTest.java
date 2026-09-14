package com.mall.admin.controller;

import com.jayway.jsonpath.JsonPath;
import com.mall.admin.domain.AdminUser;
import com.mall.admin.support.AdminTestBase;
import com.mall.admin.support.CacheKeys;
import com.mall.admin.support.JwtUtil;
import com.mall.admin.support.TokenVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/admin/auth/**} 的**真库 + 真 Redis + MockMvc** 契约测试（C1 的可执行版本）。
 *
 * <p>断言的是与单体**逐字相同**的对外表现（P7 §3 / §5 第 5 条）：
 * <pre>
 *   错误形状（**HTTP 恒 200** + 业务 code）：
 *     400 请输入账号和密码          账号/密码为空
 *     400 请求体格式不正确          请求体不是合法 JSON
 *     401 用户名或密码错误          查不到该用户 或 BCrypt 不匹配（**不区分**，避免账号枚举）
 *     403 账号已被禁用              status != 1（登录时）
 *     401 未登录                    Authorization 缺失/不是 "Bearer xxx"/空 token
 *     401 登录已失效，请重新登录     验签失败 / 过期 / 管理员不存在 / 令牌版本号不符
 *     401 登录已失效，请使用管理员账号登录   typ != admin（会员令牌打后台）
 *     403 账号已被禁用              status != 1（已登录态）
 *   成功形状：{"code":0,"message":"ok","data":{"token":"…","admin":{id,username,nickname,status}}}
 * </pre>
 *
 * <p><b>两条身份入口都测</b>（P7 §2 的过渡形态）：
 * <ul>
 *   <li>直连（只带 {@code Authorization}）→ 过渡态的本地验签，行为与今天的单体逐字相同；</li>
 *   <li>网关注入身份（{@code X-Gateway-Auth} + {@code X-Admin-Id} + {@code X-Admin-Ver}，**不带 Authorization**）
 *       → 走网关路径：这正是路由切换后的生产形态。</li>
 * </ul>
 */
class AdminAuthApiMySqlTest extends AdminTestBase {

    // ==================== POST /api/admin/auth/login ====================

    @Test
    @DisplayName("[C1] 登录成功：形状 + 令牌 claims（HS256 / typ=admin / ver=当前版本）逐字对齐单体")
    void login_success_shapeAndTokenClaims() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.admin.id").value(seedAdminId()))
                .andExpect(jsonPath("$.data.admin.username").value(SEED_ADMIN_USERNAME))
                .andExpect(jsonPath("$.data.admin.nickname").value(seedNickname()))
                .andExpect(jsonPath("$.data.admin.status").value(1))
                .andReturn();

        String token = JsonPath.read(body(result), "$.data.token");

        // 令牌头必须是 HS256（网关 GatewayJwtUtil 的验签前提）
        String header = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(header).isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");

        // claims 口径与单体逐字相同（同一把密钥 => 网关无需改一行就能验签）
        JwtUtil.Claims claims = jwtUtil.parse(token);
        assertThat(claims.type()).isEqualTo(JwtUtil.TYPE_ADMIN);
        assertThat(claims.userId()).isEqualTo(seedAdminId());
        assertThat(claims.username()).isEqualTo(SEED_ADMIN_USERNAME);
        assertThat(claims.ver())
                .isEqualTo(tokenVersionService.current(TokenVersionService.TYPE_ADMIN, seedAdminId()));
    }

    @Test
    @DisplayName("[C1] 顶层 JSON 键集合恒为 {code,message,data}；成功时 data.admin 恒为 {id,username,nickname,status}")
    void login_success_keySetsAreStable() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn();

        Map<String, Object> root = JsonPath.read(body(result), "$");
        assertThat(root.keySet()).containsExactlyInAnyOrder("code", "message", "data");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("token", "admin");

        @SuppressWarnings("unchecked")
        Map<String, Object> admin = (Map<String, Object>) data.get("admin");
        assertThat(admin.keySet()).containsExactlyInAnyOrder("id", "username", "nickname", "status");
    }

    @Test
    @DisplayName("[C1] 错误响应的顶层键集合同样不变（HTTP 恒 200 + code + data=null）")
    void errorResponse_httpAlways200_andDataNull() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"definitely-wrong\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> root = JsonPath.read(body(result), "$");
        assertThat(root.keySet()).containsExactlyInAnyOrder("code", "message", "data");
        assertThat(root.get("code")).isEqualTo(401);
        assertThat(root.get("message")).isEqualTo("用户名或密码错误");
        assertThat(root).containsEntry("data", null);
    }

    @Test
    @DisplayName("[C1] 密码错误 → 401「用户名或密码错误」")
    void login_wrongPassword_401() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"definitely-wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("[C1] 账号不存在 → 与密码错误**同一文案**（不泄漏账号是否存在）")
    void login_unknownUsername_401_sameTextAsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no_such_admin_zzz\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("[C1] 账号或密码为空 → 400「请输入账号和密码」")
    void login_blankCredentials_400() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"password\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入账号和密码"));
    }

    @Test
    @DisplayName("[C1] 请求体不是合法 JSON → 400「请求体格式不正确」")
    void login_malformedBody_400() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"));
    }

    @Test
    @DisplayName("[C1] 被禁用的管理员登录 → 403「账号已被禁用」")
    void login_disabledAdmin_403() throws Exception {
        long id = insertAdmin("p7adm_login_disabled_", 0);
        String username = usernameOf(id);

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + TEST_ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }

    @Test
    @DisplayName("登录路径**不在**拦截范围内：不带任何身份也能到达业务逻辑（与单体 excludePathPatterns 一致）")
    void loginPath_isExcludedFromAuthInterceptor() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                // 如果登录被拦截器拦下，这里会是 401「未登录」而不是 400「请输入账号和密码」
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入账号和密码"));
    }

    // ==================== GET /api/admin/auth/me（过渡态：本地验签） ====================

    @Test
    @DisplayName("me（本地令牌）→ code=0，返回当前管理员四项")
    void me_ok_withLocalToken() throws Exception {
        String token = loginSeedAdmin();

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(seedAdminId()))
                .andExpect(jsonPath("$.data.username").value(SEED_ADMIN_USERNAME))
                .andExpect(jsonPath("$.data.nickname").value(seedNickname()))
                .andExpect(jsonPath("$.data.status").value(1));
    }

    @Test
    @DisplayName("[C1] me 没有 Authorization、也没有网关注入身份 → 401「未登录」")
    void me_noIdentity_401_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("[C1] me 头里只有 'Bearer '（空 token）/ 前缀不对 → 401「未登录」")
    void me_bearerPrefixWithoutToken_401_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Token abc.def.ghi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("[C1] me 令牌结构非法 / 已过期 / 异密钥签名 → 401「登录已失效，请重新登录」")
    void me_invalidTokens_401_invalid() throws Exception {
        // 结构非法
        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer a.b.c"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));

        // 已过期（exp < now）
        mockMvc.perform(get("/api/admin/auth/me")
                        .header("Authorization", "Bearer " + expiredAdminToken(seedAdminId(), SEED_ADMIN_USERNAME)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));

        // 用另一把密钥签名（自签令牌）
        String forged = signRawToken("another-secret-0123456789abcdefghijklmnopqrstuvwxyz",
                "{\"sub\":" + seedAdminId() + ",\"name\":\"admin\",\"typ\":\"admin\",\"ver\":0,"
                        + "\"iat\":" + (java.time.Instant.now().getEpochSecond() - 10)
                        + ",\"exp\":" + (java.time.Instant.now().getEpochSecond() + 3600) + "}");
        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    @DisplayName("[C1] me 用**会员令牌**（typ=user）打后台 → 401「登录已失效，请使用管理员账号登录」")
    void me_memberToken_401_notAdmin() throws Exception {
        String memberToken = jwtUtil.createToken(seedAdminId(), "someMember", JwtUtil.TYPE_USER, 0);

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请使用管理员账号登录"));
    }

    @Test
    @DisplayName("[C1] me 令牌有效但管理员不存在（库里查不到）→ 401「登录已失效，请重新登录」")
    void me_adminNotFound_401_invalid() throws Exception {
        long ghostId = 99_999_999L;
        String token = signAdminToken(ghostId, "ghost", JwtUtil.TYPE_ADMIN);

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    @DisplayName("[C1] me 管理员被禁用（status=0）→ 403「账号已被禁用」")
    void me_disabledAdmin_403() throws Exception {
        long id = insertAdmin("p7adm_me_disabled_", 1);
        String username = usernameOf(id);
        String token = signAdminToken(id, username, JwtUtil.TYPE_ADMIN);
        setStatusInDb(id, 0);

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }

    @Test
    @DisplayName("[C1] me 令牌版本号已 bump（登出/禁用过）→ 401「登录已失效，请重新登录」")
    void me_versionMismatch_401_invalid() throws Exception {
        long id = insertAdmin("p7adm_me_ver_", 1);
        String username = usernameOf(id);
        String staleToken = signAdminToken(id, username, JwtUtil.TYPE_ADMIN);

        tokenVersionService.bump(TokenVersionService.TYPE_ADMIN, id);   // 模拟登出/禁用导致版本 +1

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + staleToken))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    // ==================== GET /api/admin/auth/me（目标形态：网关注入身份） ====================

    @Test
    @DisplayName("[P7 §2] me 带网关注入身份（**不带 Authorization**）→ code=0（网关已验签，本服务只认头）")
    void me_withGatewayIdentity_ok() throws Exception {
        long id = insertAdmin("p7adm_me_gw_", 1);
        String username = usernameOf(id);

        mockMvc.perform(asGatewayAdmin(get("/api/admin/auth/me"), id, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.status").value(1));
    }

    @Test
    @DisplayName("[安全] 伪造 X-Gateway-Auth（共享凭据不对）→ 401「未登录」，**不回落**到本地验签")
    void me_forgedGatewayAuth_401_notLoggedIn_andNoFallback() throws Exception {
        String validToken = loginSeedAdmin();   // 手里有合法令牌也不行：带了伪造凭据就是攻击形状

        mockMvc.perform(get("/api/admin/auth/me")
                        .header(GW_AUTH_HEADER, "definitely-wrong")
                        .header(GW_ADMIN_ID_HEADER, String.valueOf(seedAdminId()))
                        .header(GW_ADMIN_VER_HEADER, "0")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        // 只有 X-Admin-Id（没有共享凭据）同样不认
        mockMvc.perform(get("/api/admin/auth/me").header(GW_ADMIN_ID_HEADER, String.valueOf(seedAdminId())))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("[安全] 共享凭据正确但 X-Admin-Id 缺失/不是数字 → 401「未登录」")
    void me_gatewayIdentityWithoutAdminId_401_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me")
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_ADMIN_ID_HEADER, "not-a-number"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("[P7 §2.5] 网关注入身份 + 管理员已被禁用 → 403「账号已被禁用」")
    void me_withGatewayIdentity_disabledAdmin_403() throws Exception {
        long id = insertAdmin("p7adm_gw_disabled_", 0);

        mockMvc.perform(asGatewayAdmin(get("/api/admin/auth/me"), id, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }

    @Test
    @DisplayName("[P7 §2] 网关注入身份的版本号与 Redis 不一致 → 401「登录已失效，请重新登录」（双保险复核）")
    void me_withGatewayIdentity_staleVersion_401_invalid() throws Exception {
        long id = insertAdmin("p7adm_gw_ver_", 1);
        tokenVersionService.bump(TokenVersionService.TYPE_ADMIN, id);   // 当前版本 = 1，网关注入的是 0

        mockMvc.perform(asGatewayAdmin(get("/api/admin/auth/me"), id, 0))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    // ==================== POST /api/admin/auth/logout ====================

    @Test
    @DisplayName("[C1] 登出：code=0，令牌版本号 +1，旧令牌立刻 401")
    void logout_bumpsVersion_andInvalidatesOldToken() throws Exception {
        long id = insertAdmin("p7adm_logout_", 1);
        String username = usernameOf(id);
        String token = signAdminToken(id, username, JwtUtil.TYPE_ADMIN);
        long before = tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id);

        mockMvc.perform(post("/api/admin/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"));

        assertThat(rawValue(CacheKeys.tokenVersion(TokenVersionService.TYPE_ADMIN, id)))
                .isEqualTo(String.valueOf(before + 1));

        // 旧令牌立即失效（这就是"无状态 JWT 的主动失效"）
        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    @DisplayName("[C1] 登出也要求登录态：没有身份 → 401「未登录」")
    void logout_withoutIdentity_401() throws Exception {
        mockMvc.perform(post("/api/admin/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("[P7 §2] 登出带网关注入身份（不带 Authorization）→ code=0 且版本 +1")
    void logout_withGatewayIdentity_ok() throws Exception {
        long id = insertAdmin("p7adm_logout_gw_", 1);
        long before = tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id);

        mockMvc.perform(asGatewayAdmin(post("/api/admin/auth/logout"), id, before))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("seed 管理员确实在 mall_admin 库里（迁移保真的运行时证据）")
    void seedAdminIsReallyInMallAdminSchema() {
        assertThat(schemaName()).isEqualTo("mall_admin");
        AdminUser admin = jdbcTemplate.queryForObject(
                "SELECT id, username, nickname, status FROM sys_user WHERE username = ?",
                (rs, i) -> {
                    AdminUser u = new AdminUser();
                    u.setId(rs.getLong("id"));
                    u.setUsername(rs.getString("username"));
                    u.setNickname(rs.getString("nickname"));
                    u.setStatus(rs.getInt("status"));
                    return u;
                }, SEED_ADMIN_USERNAME);
        assertThat(admin).isNotNull();
        assertThat(admin.getStatus()).isEqualTo(1);
    }
}
