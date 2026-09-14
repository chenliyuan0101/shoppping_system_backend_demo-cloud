package com.mall.admin.support;

/**
 * 鉴权相关的<b>请求属性名</b>常量（与各服务同一套口径）。
 *
 * <p>写方是本服务的 {@code AdminAuthInterceptor}（通过校验后写入当前管理员 id），
 * 读方是各管理端 Controller（{@code @RequestAttribute}）。
 * 属性名集中定义，避免"谁 import 谁的拦截器只为借一个字符串"这类跨域依赖。
 *
 * <p>⚠️ 改常量值等于改"写读双方约定的键名"，必须同时改两侧。
 */
public final class AuthAttribute {

    private AuthAttribute() {
    }

    /** 当前登录管理员的 id（由管理端鉴权拦截器写入） */
    public static final String ADMIN_USER_ID = "adminUserId";
}
