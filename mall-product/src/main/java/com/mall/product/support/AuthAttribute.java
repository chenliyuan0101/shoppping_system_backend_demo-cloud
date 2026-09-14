package com.mall.product.support;

/**
 * 鉴权相关的<b>请求属性名</b>常量（共享内核副本，与单体 {@code com.mall.demo.common.AuthAttribute} 同值）。
 *
 * <p>为什么要有这个类：管理端鉴权拦截器通过校验后会把"当前登录管理员 id"写进请求属性，
 * 各 Controller 用 {@code @RequestAttribute} 取。属性名如果定义在拦截器上，
 * 别的业务域的控制器为了读它就得 import 拦截器 —— 单体那边就是因此产生了一条跨域依赖（架构闸门 B4）。
 * 把键名放进共享内核后，写方与读方都只依赖 {@code support}。
 *
 * <p>⚠️ 现状说明：**P6-1b 的 16 条后台端点里没有任何一条读它**（迁移过来的商品域代码没有
 * "操作人"审计字段的落库逻辑）。保留它是为了与单体同构、给 P6-3/P6-4 留好接口；
 * 别因为"没人用"就删掉，否则将来第一个需要它的控制器又会自己裸写字符串。
 */
public final class AuthAttribute {

    /** 当前登录管理员的 id（由 {@code AdminAuthInterceptor} 写入） */
    public static final String ADMIN_USER_ID = "adminUserId";

    private AuthAttribute() {
    }
}
