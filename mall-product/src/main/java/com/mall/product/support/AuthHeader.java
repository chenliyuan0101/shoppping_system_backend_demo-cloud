package com.mall.product.support;

import org.springframework.util.StringUtils;

/**
 * {@code Authorization} 头解析（会员端/管理端共用口径）：取 {@code "Bearer xxx"} 里的 token，
 * 缺失/格式不对 → 401「未登录」。
 *
 * <p>与单体 {@code com.mall.demo.common.AuthHeader} **逐字一致**（含文案）。
 * 解释一下两个看似多余的细节，别顺手"优化"掉：
 * <ul>
 *   <li>{@code startsWith("Bearer ")} 大小写敏感：单体就是敏感匹配。放宽成忽略大小写会让
 *       "两个实现口径不同"这种最难查的差异出现在鉴权路径上；</li>
 *   <li>{@code substring 后再 trim}：允许 {@code "Bearer  xxx"} 这种多空格；
 *       trim 后为空（例如只写了 {@code "Bearer "}）仍按缺失处理。</li>
 * </ul>
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
