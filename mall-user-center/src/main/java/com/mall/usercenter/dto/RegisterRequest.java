package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 注册请求。
 *
 * <p>校验分工：{@code password} 的格式约束写在**本 DTO** 上（见下方注解，由 Service 调
 * {@code RequestValidator} 触发）；{@code username} 的格式与唯一性仍在 Service——
 * 它要先 `trimToNull` 再匹配 `^[A-Za-z0-9_]{4,20}$`，"允许首尾带空格"这一点注解表达不了。
 * 接口口径见《接口文档.md》2.1。
 */
@Data
@Schema(description = "注册请求")
public class RegisterRequest {

    /** 4~20 位字母/数字/下划线 */
    @Schema(description = "登录用户名(4~20 位字母、数字或下划线)", example = "alice2026")
    private String username;

    /** 8~20 位，须含字母与数字 */
    @Schema(description = "密码(8~20 位且同时包含字母与数字)", example = "Abc123456")
    @NotBlank(message = "密码须为 8~20 位且同时包含字母与数字")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,20}$", message = "密码须为 8~20 位且同时包含字母与数字")
    private String password;

    /** 昵称，可选(缺省用 username) */
    @Schema(description = "昵称(选填，缺省为用户名)", example = "爱丽丝")
    private String nickname;

    /** 手机号，可选(提供则须唯一) */
    @Schema(description = "手机号(选填)", example = "13888888888")
    private String phone;
}
