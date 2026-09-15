package com.mall.usercenter.controller;

import com.mall.usercenter.dto.AuthResponse;
import com.mall.usercenter.dto.LoginRequest;
import com.mall.usercenter.dto.PasswordRequest;
import com.mall.usercenter.dto.RegisterRequest;
import com.mall.usercenter.dto.UserInfo;
import com.mall.usercenter.service.MemberService;
import com.mall.usercenter.support.ApiResponse;
import com.mall.common.support.MemberId;
import com.mall.usercenter.support.RateLimit;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台认证接口 /api/auth(见《接口文档.md》2.1)。
 * 白名单：register / login 免登录；logout / me / password 需登录态。
 *
 * <p><b>P3 改造点</b>：这三个接口原来收**裸 token** 并自己反查会员；
 * 现在改为收 {@code @MemberId}——身份由网关验签后注入（§4.4 ①），服务不再接触令牌。
 * 对外表现完全不变（同样的 401 文案、同样的响应体），变的是"谁负责验签"。
 */
@Tag(name = "前台-用户管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    @PostMapping("/register")
    @RateLimit(scope = "register", limit = 20, windowSeconds = 60)
    public ApiResponse<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ApiResponse.ok(memberService.register(request));
    }

    @PostMapping("/login")
    @RateLimit(scope = "login", limit = 30, windowSeconds = 60)
    public ApiResponse<AuthResponse> login(@RequestBody LoginRequest request) {
        return ApiResponse.ok(memberService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@MemberId Long memberId) {
        memberService.logout(memberId);
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me(@MemberId Long memberId) {
        return ApiResponse.ok(memberService.getUserInfo(memberId));
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@MemberId Long memberId, @RequestBody PasswordRequest request) {
        memberService.changePassword(memberId, request);
        return ApiResponse.ok();
    }
}
