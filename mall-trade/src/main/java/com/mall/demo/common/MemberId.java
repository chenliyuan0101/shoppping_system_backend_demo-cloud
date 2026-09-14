package com.mall.demo.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入"当前登录会员 id"，标注在 Controller 方法的 {@code Long} 参数上。
 *
 * <p>取值由 {@link com.mall.demo.auth.support.MemberIdArgumentResolver} 从请求头
 * {@code Authorization: Bearer xxx} 解析，校验口径与 {@link com.mall.demo.auth.support.MemberSession} 完全一致
 * （签名/有效期 → {@code typ=user} → 会员存在 → {@code status=1} → 令牌版本号），失败抛 401。
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
