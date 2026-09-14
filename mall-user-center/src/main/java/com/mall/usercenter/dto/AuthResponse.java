package com.mall.usercenter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 注册/登录成功响应：token + 用户信息。
 */
@Data
@AllArgsConstructor
public class AuthResponse {

    private String token;

    private UserInfo user;
}
