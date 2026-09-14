package com.mall.admin.support;

import org.springframework.util.StringUtils;

/**
 * Authorization 头解析（管理端）：取 "Bearer xxx" 中的 token，缺失/格式错 → {@code 401 未登录}。
 *
 * <p>逐字照抄单体 {@code com.mall.demo.common.AuthHeader}（本服务自持副本）：
 * 前缀必须是 {@code "Bearer "}（含一个空格）、token 去空白后非空，否则一律 401「未登录」。
 * ⚠️ 这条文案**同时**出现在网关 {@code AdminIdentityFilter.bearerToken} 的判定里，
 * 两侧改动必须同批（P7 §2：`401 未登录` = 头缺失/格式错）。
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
