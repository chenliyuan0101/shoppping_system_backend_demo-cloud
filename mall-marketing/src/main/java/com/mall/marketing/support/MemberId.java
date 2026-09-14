package com.mall.marketing.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入"当前登录会员 id"，标注在 Controller 方法的 {@code Long} 参数上。
 *
 * <p>取值由 {@code com.mall.marketing.config.WebConfig} 注册的解析器完成，它只读**网关注入的身份头**
 * （{@code X-Gateway-Auth} / {@code X-Member-Id}，校验见
 * {@link com.mall.marketing.config.GatewayIdentityResolver}）：本服务不验签、不接触令牌。
 * 不可信或缺失时抛 401「未登录」。
 *
 * <p>P5 批次 2 起由会员侧三个公开端点使用（{@code /api/coupon/available}、
 * {@code /api/coupon/{templateId}/receive}、{@code /api/coupon/mine}），且那里的
 * {@code memberId} **必须**来自这里，而不是请求体/请求参数——从请求体收 memberId
 * 等于让客户端自报身份，就能领别人的券、看别人的券。
 * 与单体一致：三个端点都"必须登录"，匿名一律 401「未登录」。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MemberId {
}
