package com.mall.demo.common;

import org.springframework.util.StringUtils;

/**
 * Authorization 头解析(会员端/管理端共用)：取 "Bearer xxx" 中的 token，缺失/格式错 → 401。
 */
public final class AuthHeader {

    private static final String PREFIX = "Bearer ";

    private AuthHeader() {
    }

    public static String bearer(String authorization) {
        if (StringUtils.hasText(authorization) && authorization.startsWith(PREFIX)) {
            String token = authorization.substring(PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        throw new BusinessException(401, "未登录");
    }
}
