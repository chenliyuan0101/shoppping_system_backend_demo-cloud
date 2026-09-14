package com.mall.admin.controller;

import com.mall.admin.dto.AdminLoginRequest;
import com.mall.admin.dto.AdminLoginResponse;
import com.mall.admin.service.AdminAuthService;
import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.RateLimit;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台认证 {@code /api/admin/auth}（见《接口文档.md》3.1）。
 *
 * <p><b>三个映射与单体逐字相同</b>（路径 + HTTP 方法 + 请求体 + 响应字段）：
 * <pre>
 *   POST /api/admin/auth/login   登录（**唯一放行**路径，不需要令牌）
 *   GET  /api/admin/auth/me      当前管理员
 *   POST /api/admin/auth/logout  登出
 * </pre>
 * 除 {@code /login} 外由 {@code AdminAuthInterceptor} 统一鉴权（P7 §2：判据在网关/本服务的身份解析器，
 * 网关统一后由网关注入身份头）。
 *
 * <h2>与单体的一处（刻意的）实现差异</h2>
 * 单体把**裸 token** 作为参数交给 Service（{@code @AuthToken String token}），Service 再解析一次取 id；
 * 本服务改成"Service 从身份解析器取当前管理员"——因为 P7 §2 之后身份的来源**可能是网关注入的头**
 * （那时服务端手里没有、也不该有原始令牌）。对外契约（HTTP 形状与文案）不变，C1 不受影响。
 */
@Tag(name = "后台-管理员管理")
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    /** 登录限流与单体逐字相同：10 次 / 60 秒（撞库防护不许因为搬家而变松） */
    @RateLimit(scope = "admin_login", limit = 10, windowSeconds = 60)
    @PostMapping("/login")
    public ApiResponse<AdminLoginResponse> login(@RequestBody AdminLoginRequest request) {
        return ApiResponse.ok(adminAuthService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<AdminLoginResponse.AdminInfo> me() {
        return ApiResponse.ok(adminAuthService.getAdminInfo());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        adminAuthService.logout();
        return ApiResponse.ok();
    }
}
