package com.mall.usercenter.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入"当前登录会员 id"，标注在 Controller 方法的 {@code Long} 参数上。
 *
 * <p>取值由 {@code com.mall.usercenter.config.WebConfig} 注册的解析器完成，它只读**网关注入的身份头**
 * （{@code X-Gateway-Auth} / {@code X-Member-Id}，校验见
 * {@link com.mall.usercenter.config.GatewayIdentityResolver}）：本服务不验签、不接触令牌。
 * 不可信或缺失时抛 401「未登录」。
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
