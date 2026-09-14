package com.mall.review.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入"当前登录会员 id"，标注在 Controller 方法的 {@code Long} 参数上。
 *
 * <p>取值由 {@code com.mall.review.config.WebConfig} 注册的解析器完成，它只读**网关注入的身份头**
 * （{@code X-Gateway-Auth} / {@code X-Member-Id}，校验见
 * {@link com.mall.review.config.GatewayIdentityResolver}）：本服务不验签、不接触令牌。
 * 不可信或缺失时抛 401「未登录」。
 *
 * <p>评价域大量接口需要它（提交评价、我的评价、删除自己的评价），
 * 且 P4 明确要求"归属校验全部本地"——会员身份是**唯一**必须来自外部的输入，
 * 因此它必须走这条被校验过的路径，而不是从请求体里收一个 {@code memberId}
 * （那等于让客户端自报身份）。
 *
 * <pre>{@code
 * @PostMapping
 * public ApiResponse<Long> submit(@MemberId Long memberId, @RequestBody CommentSubmitRequest req) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MemberId {
}
