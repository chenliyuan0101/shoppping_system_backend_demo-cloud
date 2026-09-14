package com.mall.demo.common;

/**
 * 鉴权相关的<b>请求属性名</b>常量。
 *
 * <p>背景：管理端鉴权拦截器在通过校验后，会把"当前登录管理员 id"写进请求属性，
 * 供各 Controller 用 {@code @RequestAttribute} 取值。此前该属性名常量定义在
 * {@code com.mall.demo.admin.support.AdminAuthInterceptor} 上，于是<b>别的业务域</b>的
 * 管理端控制器（如 {@code oms.controller.AdminRefundController}）为了读这个属性，
 * 不得不 import admin 域的拦截器——一处纯粹的"借常量"却造成了跨域依赖（架构闸门里的 B4）。
 *
 * <p>把属性名放进共享内核后：写方（拦截器）与读方（各 Controller）都只依赖 {@code common}，
 * 谁也不再依赖谁。这与 {@link MqTopology}、{@code common.constant.*} 是同一个套路——
 * <b>跨域共享的必须是"契约/词汇"，而不是对方的实现类。</b>
 *
 * <p>⚠️ 改常量值等于改"写读双方约定的键名"，必须同时改两侧；集中定义就是为了避免各处裸写字符串。
 */
public final class AuthAttribute {

    private AuthAttribute() {
    }

    /** 当前登录管理员的 id（由管理端鉴权拦截器写入，Controller 用 {@code @RequestAttribute} 读取） */
    public static final String ADMIN_USER_ID = "adminUserId";
}
