package com.mall.usercenter.controller;

import com.mall.usercenter.config.GatewayIdentityResolver;
import com.mall.usercenter.dto.AuthResponse;
import com.mall.usercenter.dto.UserInfo;
import com.mall.usercenter.service.MemberService;
import com.mall.usercenter.support.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.MemberId;

/**
 * /api/auth Controller 切片测试(不启动数据库，@WebMvcTest + mock Service)。
 *
 * <p><b>与单体版本的关键差别</b>：单体里 {@code @MemberId} 解析器依赖 {@code MemberSession}，
 * 切片里只能把它 mock 掉（于是"解析器"本身根本没被测到）；这里 mock 的是 Service，
 * **参数解析器用的是真实现**（{@link GatewayIdentityResolver}）：测试直接在请求里伪造
 * "网关注入的三个头"——这正是生产环境的常态路径，因此断言的是端到端的对外表现：
 * 有可信身份 → Service 收到正确的 memberId；没身份/凭据不对 → 401「未登录」。
 *
 * <p>切片里没有真库、没有真 Redis：{@code mall.gateway.auth-token} 由
 * {@code @TestPropertySource} 注入（不能写在 application.properties 里，会被 dev profile 盖掉）。
 */
@WebMvcTest(controllers = AuthController.class)
@Import(GatewayIdentityResolver.class)
@TestPropertySource(properties = "mall.gateway.auth-token=test-gateway-token")
class AuthControllerTest {

    private static final String GW_AUTH = "X-Gateway-Auth";
    private static final String GW_MEMBER_ID = "X-Member-Id";
    private static final String GW_MEMBER_VER = "X-Member-Ver";
    private static final String GW_SECRET = "test-gateway-token";

    private static final long MEMBER_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @Test
    @DisplayName("POST /api/auth/register 成功 → code=0 且带 token/user")
    void register_ok() throws Exception {
        UserInfo user = UserInfo.builder().id(1L).username("tom2026").nickname("汤姆").build();
        when(memberService.register(any())).thenReturn(new AuthResponse("token-abc", user));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tom2026\",\"password\":\"Abc123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("token-abc"))
                .andExpect(jsonPath("$.data.user.username").value("tom2026"));
    }

    @Test
    @DisplayName("POST /api/auth/login 密码错误 → HTTP 200, code=401")
    void login_wrongPassword() throws Exception {
        when(memberService.login(any()))
                .thenThrow(new BusinessException(401, "用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"tom2026\",\"password\":\"wrong123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("GET /api/auth/me 没有任何身份 → 401「未登录」（真解析器：不可信即拒绝）")
    void me_withoutIdentity() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("[安全] GET /api/auth/me 只有 Authorization 令牌（无网关注入身份）→ 401「未登录」")
    void me_withTokenButNoGatewayIdentity() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOjd9.sig"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("[安全] 客户端手写 X-Member-Id（凭据不对）→ 401「未登录」，Service 一次都没被调用")
    void me_forgedGatewayHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(GW_AUTH, "definitely-wrong")
                        .header(GW_MEMBER_ID, String.valueOf(MEMBER_ID))
                        .header(GW_MEMBER_VER, "0"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        mockMvc.perform(get("/api/auth/me").header(GW_MEMBER_ID, String.valueOf(MEMBER_ID)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verify(memberService, never()).getUserInfo(anyLong());
    }

    @Test
    @DisplayName("GET /api/auth/me 带可信网关注入身份 → code=0，且 Service 收到的正是 X-Member-Id")
    void me_withGatewayIdentity() throws Exception {
        when(memberService.getUserInfo(MEMBER_ID))
                .thenReturn(UserInfo.builder().id(MEMBER_ID).username("tom2026").build());

        mockMvc.perform(get("/api/auth/me")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_MEMBER_ID, String.valueOf(MEMBER_ID))
                        .header(GW_MEMBER_VER, "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(MEMBER_ID))
                .andExpect(jsonPath("$.data.username").value("tom2026"));

        verify(memberService).getUserInfo(MEMBER_ID);
    }

    @Test
    @DisplayName("POST /api/auth/logout 带可信网关注入身份 → code=0，Service 收到该 memberId")
    void logout_ok() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_MEMBER_ID, String.valueOf(MEMBER_ID))
                        .header(GW_MEMBER_VER, "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(memberService).logout(MEMBER_ID);
    }

    @Test
    @DisplayName("PUT /api/auth/password 带可信网关注入身份 → code=0，Service 收到该 memberId")
    void changePassword_ok() throws Exception {
        mockMvc.perform(put("/api/auth/password")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_MEMBER_ID, String.valueOf(MEMBER_ID))
                        .header(GW_MEMBER_VER, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Abc123456\",\"newPassword\":\"New123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(memberService).changePassword(eq(MEMBER_ID), any());
    }

    @Test
    @DisplayName("畸形 JSON 请求体 → 400「请求体格式不正确」(以前会掉进 Exception 兜底返回 500)")
    void malformedBody_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"tom2026\",\"password\":]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"));
    }
}
