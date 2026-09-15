package com.mall.trade.auth.support;

import com.mall.trade.common.AuthHeader;
import com.mall.trade.common.AuthToken;
import com.mall.common.support.MemberId;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 解析 {@link MemberId} / {@link AuthToken} 两个注解参数，替代各 Controller 里
 * {@code @RequestHeader(value = "Authorization", required = false) String auth} + {@code memberSession.requireUserId(auth)}
 * 的重复样板。
 *
 * <p>真正的会员校验**仍然只有 {@link MemberSession} 一处**，本类只负责"把请求头取出来递进去"，
 * 因此不会出现两套校验口径。取到 {@code Long} 时已经保证会员存在且未被禁用。
 *
 * <p>参数解析发生在 Controller 方法调用之前；一次请求内 {@link MemberSession} 的结果带请求级缓存，
 * 所以限流切面与本解析器各自解析一次也只查一次库。
 */
@Component
@RequiredArgsConstructor
public class MemberIdArgumentResolver implements HandlerMethodArgumentResolver {

    /** 认证请求头名 */
    private static final String HEADER_AUTHORIZATION = "Authorization";

    private final MemberSession memberSession;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(MemberId.class)) {
            return Long.class.equals(parameter.getParameterType());
        }
        if (parameter.hasParameterAnnotation(AuthToken.class)) {
            return String.class.equals(parameter.getParameterType());
        }
        return false;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String authorization = webRequest.getHeader(HEADER_AUTHORIZATION);
        if (parameter.hasParameterAnnotation(MemberId.class)) {
            return memberSession.requireUserId(authorization);
        }
        return AuthHeader.bearer(authorization);
    }
}
