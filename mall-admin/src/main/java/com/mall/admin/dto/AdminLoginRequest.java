package com.mall.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 后台登录请求（逐字平移单体 {@code com.mall.demo.admin.dto.AdminLoginRequest}）。
 *
 * <p>字段名 {@code username}/{@code password} 是**对外契约**（C1：请求体形状逐字不变），不许改。
 *
 * <p>注意：示例里**不写真实口令** —— 调试页/文档里出现可直接使用的后台口令，等于把默认账号公开出去。
 * 演示环境的初始口令见《数据库建库文档.md》"上线前检查清单"。
 */
@Data
@Schema(description = "后台登录请求")
public class AdminLoginRequest {

    @Schema(description = "管理员登录名", example = "admin")
    private String username;

    @Schema(description = "密码", example = "<你的后台口令>")
    private String password;
}
