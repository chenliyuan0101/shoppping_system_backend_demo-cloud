package com.mall.review.config;

import com.mall.common.support.MemberId;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 配置（评价服务）：内部接口鉴权 + {@code @MemberId} 参数解析。
 *
 * <p><b>身份从哪来（P3/P4 的架构要点）</b>：改造前每个服务自己"验签 + 查 ums_member"；
 * 拆分后由**网关**验签并把 {@code X-Member-Id} 注入进来（方案 §4.4）。因此本解析器读的是
 * 网关注入的头（校验见 {@link GatewayIdentityResolver}），而**绝不**回退成"查令牌"——
 * 本服务既不持有验签逻辑，也不该从令牌里反推"我是谁"。
 *
 * <p>解析失败一律 401「未登录」（文案与改造前一致）：没有可信身份就是未登录，
 * 不区分"没带头"和"带错头"。
 *
 * <p>本批还没有用到 {@code @MemberId} 的接口（只有 {@code /internal/**} 两个只读端点），
 * 但解析器与身份解析器必须**现在**就位：下一批的提交/删除评价是写操作，
 * 届时再补这一环，等于在写路径上临时发明一套身份读取方式——那正是漏洞的来源。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final InternalApiAuthInterceptor internalApiAuthInterceptor;

    private final GatewayIdentityResolver gatewayIdentityResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiAuthInterceptor)
                .addPathPatterns("/internal/**");
    }

    /**
     * {@code @MemberId Long} 参数解析器：把"网关注入的会员 id"递给 Controller。
     *
     * <p>只支持 {@code Long} 参数：声明成别的类型（如 {@code long} 基本类型）会解析不到而 500——
     * 不支持就明确不支持，不要静默注入一个 0 进去（那会让"归属校验"变成"归 0 号会员"）。
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(MemberId.class)
                        && Long.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
                // 不可信/缺失 → BusinessException(401, "未登录")，由 GlobalExceptionHandler 转成 HTTP 200 + code=401
                return gatewayIdentityResolver.resolve(request);
            }
        });
    }
}
