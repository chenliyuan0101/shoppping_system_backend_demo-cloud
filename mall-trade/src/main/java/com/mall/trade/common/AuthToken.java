package com.mall.trade.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入"已剥离 {@code Bearer } 前缀的裸 token"，标注在 Controller 方法的 {@code String} 参数上。
 *
 * <p>取值由 {@link com.mall.trade.auth.support.MemberIdArgumentResolver} 从请求头
 * {@code Authorization} 取原值后交给 {@link AuthHeader#bearer(String)} 处理；
 * 请求头缺失或格式不对时由该方法抛 401「未登录」（与改造前 Controller 里手写
 * {@code AuthHeader.bearer(auth)} 的行为完全一致）。
 *
 * <p>仅用于**必须拿到 token 本身**的接口（登出/查询当前登录信息/改密），业务接口请用 {@link MemberId}。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuthToken {
}
