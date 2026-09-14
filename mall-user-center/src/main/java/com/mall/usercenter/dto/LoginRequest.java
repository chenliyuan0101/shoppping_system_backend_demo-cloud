package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录请求：account 兼容用户名或手机号。
 *
 * <p>示例里不写真实可用的演示口令（调试页会原样展示，等于公开账号）。
 */
@Data
@Schema(description = "登录请求")
public class LoginRequest {

    @Schema(description = "用户名或手机号", example = "alice2026")
    private String account;

    @Schema(description = "密码", example = "<你的登录口令>")
    private String password;
}
