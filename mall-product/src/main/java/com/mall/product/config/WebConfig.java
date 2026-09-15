package com.mall.product.config;

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
 * Web 配置（商品服务）：内部接口鉴权 + {@code @MemberId} 参数解析。
 *
 * <p><b>身份从哪来（P3/P4 的架构要点）</b>：改造前每个服务自己"验签 + 查 ums_member"；
 * 拆分后由**网关**验签并把 {@code X-Member-Id} 注入进来（方案 §4.4）。因此本解析器读的是
 * 网关注入的头（校验见 {@link GatewayIdentityResolver}），而**绝不**回退成"查令牌"。
 * 解析失败一律 401「未登录」（文案与改造前一致）。
 *
 * <p>两条链路必须分得清（与 mall-marketing 同一处纪律）：
 * <ul>
 *   <li>{@code /internal/**}：走 {@link InternalApiAuthInterceptor} 的 {@code X-Internal-Token}，
 *       {@code memberId}/{@code orderNo} 是调用方（trade）在服务端上下文里传进来的，
 *       **不读身份头**；</li>
 *   <li>会员侧公开端点：走网关注入的身份头。</li>
 * </ul>
 *
 * <p>⚠️ <b>本批的真实边界（已知、已上报，不要在验收时当成"做完了"）</b>
 * <ol>
 *   <li>拦截器挂 {@code /internal/**} 与 {@code /api/admin/**} 两处；</li>
 *   <li>{@code /api/admin/**}（16 条）**已有本服务自己的鉴权闸**（P6-1b）：
 *       {@link AdminAuthInterceptor} 校验 HS256 签名 + 有效期 + {@code typ=admin}。
 *       ⚠️ 但它**不是完整的鉴权** —— "管理员是否存在 / 是否被禁用 / 令牌版本号"这三项要读
 *       {@code sys_user} 与认证域的令牌版本，本服务做不了，**仍由单体保留、P7 随认证域移交
 *       {@code mall-admin} BFF**。完整的说明写在 {@link AdminAuthInterceptor} 的类注释里，
 *       **不要**把它简化成"鉴权已就绪"；</li>
 *   <li>{@code @MemberId} 解析器本批**没有任何端点使用**，先与其它服务同构保留（见 MemberId 类注释）。</li>
 * </ol>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final InternalApiAuthInterceptor internalApiAuthInterceptor;

    private final AdminAuthInterceptor adminAuthInterceptor;

    private final GatewayIdentityResolver gatewayIdentityResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiAuthInterceptor)
                .addPathPatterns("/internal/**");

        // 管理端闸（P6-1b）：**不排除任何路径**。
        // 本服务没有 /api/admin/auth/**（登录端点在单体/P7 的 admin BFF）——
        // "排除一条不存在的路径"只会让读代码的人以为这里能登录，所以一条都不排除。
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**");
    }

    /**
     * {@code @MemberId Long} 参数解析器：把"网关注入的会员 id"递给 Controller。
     *
     * <p>只支持 {@code Long} 参数：声明成基本类型 {@code long} 会解析不到而 500——
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
