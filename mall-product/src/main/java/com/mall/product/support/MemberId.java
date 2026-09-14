package com.mall.product.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入"当前登录会员 id"，标注在 Controller 方法的 {@code Long} 参数上。
 *
 * <p>取值由 {@code com.mall.product.config.WebConfig} 里的参数解析器经
 * {@link com.mall.product.config.GatewayIdentityResolver} 从**网关注入的头**
 * （{@code X-Gateway-Auth} + {@code X-Member-Id}）取出——**本服务不验签**（方案 §4.4 ①），
 * 拆服务后验签由网关统一做一次。失败抛 401「未登录」（文案与改造前逐字一致）。
 *
 * <p>⚠️ P6-1 期商品域**还没有**任何端点用到本注解（前台 4 条读是游客可访问的，
 * 后台 16 条当前由单体/网关鉴权）。解析器与注解先与其它服务保持同构，
 * 供 P6-3/P6-4 的会员侧端点使用；"没有用到"这件事本身在文件映射表里写明。
 *
 * <p>这样写的好处：Controller 不再出现 {@code @RequestHeader ... String auth} + {@code requireUserId(auth)}
 * 的重复样板，也不会因为漏解析请求头而把 {@code null} 传进 Service。
 *
 * <pre>{@code
 * @GetMapping("/list")
 * public ApiResponse<List<CartItemVO>> list(@MemberId Long memberId) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MemberId {
}
