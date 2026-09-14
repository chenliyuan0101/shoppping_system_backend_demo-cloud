package com.mall.admin.controller;

import com.mall.admin.config.AdminAuthInterceptor;
import com.mall.admin.config.AdminIdentityResolver;
import com.mall.admin.config.WebConfig;
import com.mall.admin.domain.AdminUser;
import com.mall.admin.dto.AdminLoginResponse;
import com.mall.admin.mapper.AdminUserMapper;
import com.mall.admin.service.AdminAuthService;
import com.mall.admin.support.AdminSession;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.JwtUtil;
import com.mall.admin.support.TokenVersionService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/admin/auth/**} 的 Controller **切片测试**（不连库、不连 Redis，mock Service/Mapper）。
 *
 * <p>与真库套件（{@code AdminAuthApiMySqlTest}）的分工：这里只钉**请求形状 → 文案**这一层，
 * 因此跑得快、也不受库/Redis 状态影响；真库套件负责"落库/落 Redis/令牌版本号真的动了"。
 *
 * <p>关键是这里用的是**真**身份链（{@link AdminIdentityResolver} + {@link AdminSession} + 真
 * {@link JwtUtil}，只 mock 了"查库"这一层）：因此断言的是端到端的对外表现 ——
 * 头缺失/格式错 → {@code 401 未登录}；网关注入身份 → 按 {@code X-Admin-Id} 查库取身份；
 * 伪造凭据 → {@code 401 未登录}（**不回路**到 Authorization 验签）。
 */
@WebMvcTest(controllers = AdminAuthController.class)
@Import({WebConfig.class, AdminAuthInterceptor.class, AdminIdentityResolver.class, AdminSession.class, JwtUtil.class})
@TestPropertySource(properties = {
        // ⚠️ 必须写在注解里：application-dev.yaml 的同名键优先级更高，写进 application.properties 会被盖掉
        "mall.jwt.secret=mall-dev-only-secret-0123456789abcdef-change-me",
        "mall.gateway.auth-token=test-gateway-token"
})
class AdminAuthControllerTest {

    private static final String GW_AUTH = "X-Gateway-Auth";
    private static final String GW_ADMIN_ID = "X-Admin-Id";
    private static final String GW_ADMIN_VER = "X-Admin-Ver";
    private static final String GW_SECRET = "test-gateway-token";
    private static final long ADMIN_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private AdminAuthService adminAuthService;

    /** 切片里没有真库：只把"查 sys_user"这一层换成替身，其余校验逻辑都是真的 */
    @MockitoBean
    private AdminUserMapper adminUserMapper;

    @MockitoBean
    private TokenVersionService tokenVersionService;

    private AdminUser enabledAdmin() {
        AdminUser admin = new AdminUser();
        admin.setId(ADMIN_ID);
        admin.setUsername("admin");
        admin.setNickname("超级管理员");
        admin.setStatus(1);
        return admin;
    }

    // ==================== 未登录：401「未登录」 ====================

    @Test
    @DisplayName("[C1] GET /api/admin/auth/me 无任何身份 → 401「未登录」")
    void me_withoutAnyIdentity_401() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("[C1] Authorization 存在但格式不对（空 token / 非 Bearer）→ 401「未登录」")
    void me_badAuthorizationFormat_401() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer "))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Token abc.def.ghi"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verify(adminAuthService, never()).getAdminInfo();
    }

    // ==================== 令牌形状不对：401「登录已失效…」 ====================

    @Test
    @DisplayName("[C1] 结构非法令牌 → 401「登录已失效，请重新登录」")
    void me_malformedToken_401() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    @Test
    @DisplayName("[C1] typ != admin（会员令牌打后台）→ 401「登录已失效，请使用管理员账号登录」")
    void me_memberToken_401_notAdmin() throws Exception {
        String memberToken = jwtUtil.createToken(ADMIN_ID, "someone", JwtUtil.TYPE_USER, 0);

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请使用管理员账号登录"));

        // 连库都不用查：typ 判定在查库之前（顺序与单体 AdminSession.resolve 一致）
        verify(adminUserMapper, never()).selectById(anyLong());
    }

    // ==================== 网关注入身份（P7 §2 的目标形态） ====================

    @Test
    @DisplayName("[P7 §2] 带可信网关注入身份 → 按 X-Admin-Id 查库取身份，code=0")
    void me_withGatewayIdentity_ok() throws Exception {
        when(adminUserMapper.selectById(ADMIN_ID)).thenReturn(enabledAdmin());
        when(tokenVersionService.matches(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(adminAuthService.getAdminInfo()).thenReturn(AdminLoginResponse.AdminInfo.builder()
                .id(ADMIN_ID).username("admin").nickname("超级管理员").status(1).build());

        mockMvc.perform(get("/api/admin/auth/me")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_ADMIN_ID, String.valueOf(ADMIN_ID))
                        .header(GW_ADMIN_VER, "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(ADMIN_ID))
                .andExpect(jsonPath("$.data.username").value("admin"));

        verify(adminUserMapper).selectById(ADMIN_ID);
    }

    @Test
    @DisplayName("[安全] 伪造 X-Gateway-Auth → 401「未登录」，即使同时带了合法令牌也不回落")
    void me_forgedGatewayAuth_401_andNoFallback() throws Exception {
        String validToken = jwtUtil.createToken(ADMIN_ID, "admin", JwtUtil.TYPE_ADMIN, 0);

        mockMvc.perform(get("/api/admin/auth/me")
                        .header(GW_AUTH, "wrong-secret")
                        .header(GW_ADMIN_ID, String.valueOf(ADMIN_ID))
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verify(adminAuthService, never()).getAdminInfo();
    }

    @Test
    @DisplayName("[安全] 只有 X-Admin-Id（没有共享凭据）→ 401「未登录」")
    void me_adminIdWithoutGatewayAuth_401() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me").header(GW_ADMIN_ID, String.valueOf(ADMIN_ID)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verify(adminUserMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("[安全] 可信凭据但管理员被禁用（status=0）→ 403「账号已被禁用」")
    void me_gatewayIdentity_disabledAdmin_403() throws Exception {
        AdminUser disabled = enabledAdmin();
        disabled.setStatus(0);
        when(adminUserMapper.selectById(ADMIN_ID)).thenReturn(disabled);

        mockMvc.perform(get("/api/admin/auth/me")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_ADMIN_ID, String.valueOf(ADMIN_ID)))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }

    @Test
    @DisplayName("[安全] 可信凭据但库里查不到该管理员 → 401「登录已失效，请重新登录」")
    void me_gatewayIdentity_adminNotFound_401() throws Exception {
        when(adminUserMapper.selectById(ADMIN_ID)).thenReturn(null);

        mockMvc.perform(get("/api/admin/auth/me")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_ADMIN_ID, String.valueOf(ADMIN_ID)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
    }

    // ==================== 登录 ====================

    @Test
    @DisplayName("POST /api/admin/auth/login 成功 → code=0 + token/admin（委托 Service）")
    void login_ok_delegatesToService() throws Exception {
        when(adminAuthService.login(any())).thenReturn(new AdminLoginResponse("token-abc",
                AdminLoginResponse.AdminInfo.builder()
                        .id(1L).username("admin").nickname("超级管理员").status(1).build()));

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.token").value("token-abc"))
                .andExpect(jsonPath("$.data.admin.username").value("admin"));

        verify(adminAuthService).login(any());
    }

    @Test
    @DisplayName("[C1] 登录请求体不是合法 JSON → 400「请求体格式不正确」")
    void login_malformedBody_400() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"));
    }

    @Test
    @DisplayName("[C1] 登录业务异常（401/403）→ HTTP 200 + 原文案")
    void login_businessErrors_areHttp200WithSameText() throws Exception {
        // ⚠️ 必须用 doThrow(...).when(mock) 而不是 when(mock.x()).thenThrow(...)：
        //    同一用例里第二次改桩时，when(mock.login(any())) 会**真的调用**已被桩成抛异常的方法，
        //    异常在 when(...) 求值时就抛出来了（实测踩过：报 "BusinessException" 且栈顶指向本行）。
        doThrow(new BusinessException(401, "用户名或密码错误")).when(adminAuthService).login(any());
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        doThrow(new BusinessException(403, "账号已被禁用")).when(adminAuthService).login(any());
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }

    @Test
    @DisplayName("登录与 me/logout 的放行规则：login 不需要身份，me/logout 需要")
    void loginPathIsPublicButMeAndLogoutAreNot() throws Exception {
        when(adminAuthService.getAdminInfo()).thenReturn(AdminLoginResponse.AdminInfo.builder()
                .id(ADMIN_ID).username("admin").status(1).build());

        // login 放行（这里用 mock 的 Service 成功返回，证明它没被拦截器拦下）
        when(adminAuthService.login(any())).thenReturn(new AdminLoginResponse("t",
                AdminLoginResponse.AdminInfo.builder().id(ADMIN_ID).username("admin").status(1).build()));
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(0));

        // me / logout 不放行：没有身份就是 401「未登录」
        mockMvc.perform(get("/api/admin/auth/me"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
        mockMvc.perform(post("/api/admin/auth/logout"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("登出（带可信网关注入身份）→ code=0，委托 Service")
    void logout_withGatewayIdentity_ok() throws Exception {
        when(adminUserMapper.selectById(ADMIN_ID)).thenReturn(enabledAdmin());
        when(tokenVersionService.matches(anyString(), anyLong(), anyLong())).thenReturn(true);

        mockMvc.perform(post("/api/admin/auth/logout")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_ADMIN_ID, String.valueOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"));

        verify(adminAuthService).logout();
    }

    @Test
    @DisplayName("参数解析器把身份交给 Service 之前就完成校验：Service 返回什么不影响 403/401 的判定")
    void identityChecksHappenBeforeServiceCall() throws Exception {
        when(adminUserMapper.selectById(eq(ADMIN_ID))).thenReturn(null);

        mockMvc.perform(get("/api/admin/auth/me")
                        .header(GW_AUTH, GW_SECRET)
                        .header(GW_ADMIN_ID, String.valueOf(ADMIN_ID)))
                .andExpect(jsonPath("$.code").value(401));

        verify(adminAuthService, never()).getAdminInfo();
    }
}
