package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 修改密码请求(登录态)。
 */
@Data
@Schema(description = "修改密码请求")
public class PasswordRequest {

    @Schema(description = "原密码", example = "Abc123456")
    private String oldPassword;

    @Schema(description = "新密码(8~20 位且同时包含字母与数字)", example = "Def654321")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,20}$", message = "新密码须为 8~20 位且同时包含字母与数字")
    private String newPassword;
}
