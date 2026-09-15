package com.mall.usercenter.controller;

import com.jayway.jsonpath.JsonPath;
import com.mall.usercenter.support.CacheKeys;
import com.mall.usercenter.support.JwtUtil;
import com.mall.usercenter.support.TokenVersionService;
import com.mall.usercenter.support.UserCenterTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.MemberId;

/**
 * 会员认证域的<b>真库 + 真 Redis 端到端测试</b>：五个对外路径全部走一遍 MockMvc。
 *
 * <p>它证明的是"搬过来之后对外契约没变，而身份来源变了"：
 * <ol>
 *   <li>{@code /api/auth/register} → {@code /api/auth/login}：返回的 token 仍是 HS256、claims 仍是
 *       {@code sub/name/typ/ver/iat/exp}（本服务签发，网关验签）；</li>
 *   <li>{@code /api/auth/me}：**故意不带 {@code Authorization}**，只带网关注入的三个头
 *       （{@code X-Gateway-Auth/X-Member-Id/X-Member-Ver}）就能取到会员——身份来自网关，不是来自令牌；</li>
 *   <li>没有身份 / 凭据不对 / 会员不存在 / 会员被禁用：四种 401 文案分别是
 *       「未登录」「未登录」「登录已失效，请重新登录」「账号已被禁用」；</li>
 *   <li>改密与登出仍然"立即失效旧 token"：Redis 里 {@code mall:token:ver:user:{id}} 被 +1，
 *       而**拒绝旧 token 的那一次校验发生在网关**（本服务不验签）——所以这里直接断言
 *       "旧 token 的 ver 与 Redis 当前版本不再一致"（这正是网关会 401 的判据）。</li>
 * </ol>
 *
 * <p>数据全部自建自清（唯一用户名 + 物理删除），可重复执行；不依赖本机是否开着 Nacos（测试配置已关闭注册）。
 */
class AuthEndToEndMySqlTest extends UserCenterTestBase {

    private static final String PASSWORD = "Db123456";
    private static final String NEW_PASSWORD = "New123456";

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenVersionService tokenVersionService;

    private final List<String> createdUsers = new ArrayList<>();
    private long memberId = -1L;

    @AfterEach
    void cleanUp() {
        if (memberId > 0) {
            // 令牌版本号是本套件往 Redis 里写的唯一东西，用完即删，避免影响其它套件/下一次运行
            redisTemplate.delete(CacheKeys.tokenVersion(TokenVersionService.TYPE_USER, memberId));
        }
        for (String username : createdUsers) {
            jdbcTemplate.update("DELETE FROM ums_member WHERE username = ?", username);
        }
        createdUsers.clear();
        memberId = -1L;
    }

    @Test
    @DisplayName("[端到端] 注册 → 登录 → /me(网关注入身份) → 改密 → 登出：旧 token 的版本号被判为失效")
    void registerLoginMeChangePasswordLogout() throws Exception {
        assertTrue(redisUp(), "本套件需要真实 Redis：令牌版本号（旧 token 立即失效的唯一机制）就在 Redis 里");
        String username = register("uce2e_");

        // ① 登录：签发 HS256 token，版本号从 0 开始
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andReturn();
        String token = JsonPath.read(body(login), "$.data.token");

        JwtUtil.Claims claims = jwtUtil.parse(token);
        assertEquals(memberId, claims.userId().longValue(), "token 的 sub 必须是会员 id");
        assertEquals(username, claims.username());
        assertEquals(JwtUtil.TYPE_USER, claims.type(), "前台会员 token 的 typ 必须是 user");
        assertEquals(0L, claims.ver(), "新会员的令牌版本号从 0 开始");

        // ② /api/auth/me：**不带 Authorization**，只靠网关注入的身份
        mockMvc.perform(get("/api/auth/me")
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId))
                        .header(GW_MEMBER_VER_HEADER, String.valueOf(claims.ver())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(memberId))
                .andExpect(jsonPath("$.data.username").value(username));

        // ③ 没有任何身份 → 401「未登录」（HTTP 仍是 200）
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        // ④ 改密：落库新密文 + 令牌版本号 +1
        mockMvc.perform(put("/api/auth/password")
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId))
                        .header(GW_MEMBER_VER_HEADER, String.valueOf(claims.ver()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(1L, tokenVersionService.current(TokenVersionService.TYPE_USER, memberId),
                "改密必须 bump 令牌版本号（mall:token:ver:user:{id}），否则旧 token 还能用");
        assertFalse(tokenVersionService.matches(TokenVersionService.TYPE_USER, memberId, claims.ver()),
                "改密后旧 token 的 ver 与 Redis 不一致 —— 这正是网关下一次身份校验会 401 的判据");

        // ⑤ 新密码可登录、旧密码不能
        MvcResult relogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, PASSWORD)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        JwtUtil.Claims fresh = jwtUtil.parse(JsonPath.read(body(relogin), "$.data.token"));
        assertEquals(1L, fresh.ver(), "改密后新签发的 token 必须带新的版本号");

        // ⑥ 登出：版本号再 +1；此前那枚 token 同样立即失效
        mockMvc.perform(post("/api/auth/logout")
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId))
                        .header(GW_MEMBER_VER_HEADER, String.valueOf(fresh.ver())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(2L, tokenVersionService.current(TokenVersionService.TYPE_USER, memberId),
                "登出必须 bump 令牌版本号");
        assertFalse(tokenVersionService.matches(TokenVersionService.TYPE_USER, memberId, fresh.ver()),
                "登出后旧 token 不再通过版本比对 → 网关会以 401「登录已失效，请重新登录」拦住它");
    }

    @Test
    @DisplayName("[安全] 手写 X-Member-Id + 错误凭据 → 401「未登录」（不能冒充真实会员）")
    void forgedGatewayHeadersCannotImpersonate() throws Exception {
        String username = register("ucfake_");

        mockMvc.perform(get("/api/auth/me")
                        .header(GW_AUTH_HEADER, "definitely-wrong")
                        .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId))
                        .header(GW_MEMBER_VER_HEADER, "0"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        // 只写 X-Member-Id（客户端最常见的"伪造"写法）同样不认
        mockMvc.perform(get("/api/auth/me").header(GW_MEMBER_ID_HEADER, String.valueOf(memberId)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        assertTrue(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ums_member WHERE username = ?", Long.class, username) > 0,
                "会员确实存在于库里——401 不是因为「查不到人」，而是因为身份不可信");
    }

    @Test
    @DisplayName("[契约] 凭据可信但会员不存在 → 401「登录已失效，请重新登录」")
    void trustedIdentityForMissingMember() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_MEMBER_ID_HEADER, "999999999")
                        .header(GW_MEMBER_VER_HEADER, "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    @DisplayName("[契约] 会员被禁用 → 401「账号已被禁用」（版本号正确也拦住）")
    void disabledMemberRejected() throws Exception {
        String username = register("ucdis_");
        jdbcTemplate.update("UPDATE ums_member SET status = 0 WHERE id = ?", memberId);

        mockMvc.perform(get("/api/auth/me")
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId))
                        .header(GW_MEMBER_VER_HEADER,
                                String.valueOf(tokenVersionService.current(TokenVersionService.TYPE_USER, memberId))))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));

        // 登录侧同样拒绝
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, PASSWORD)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }

    /** 真实注册一个会员（走 HTTP，与生产同一条路径），并记下 id 供后续请求使用 */
    private String register(String prefix) throws Exception {
        String username = prefix + (System.nanoTime() % 100_000_000L);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\","
                                + "\"nickname\":\"端到端测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn();
        createdUsers.add(username);
        memberId = ((Number) JsonPath.read(body(result), "$.data.user.id")).longValue();
        return username;
    }

    private static String loginBody(String account, String password) {
        return "{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}";
    }
}
