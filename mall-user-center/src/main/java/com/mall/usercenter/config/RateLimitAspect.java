package com.mall.usercenter.config;

import com.mall.usercenter.support.BusinessException;
import com.mall.usercenter.support.CacheKeys;
import com.mall.usercenter.support.CacheService;
import com.mall.usercenter.support.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 限流切面：对标注 {@link RateLimit} 的接口做固定窗口计数。
 * 计数 key = mall:rl:{scope}:{identity}；超限抛 429 业务异常(统一响应体)。
 * Redis 不可用或计数返回 -1 时放行。
 *
 * <p><b>与单体版本的唯一差别是"身份从哪来"</b>：单体里 {@code RateLimit.By.USER} 维度要自己
 * 验签取会员 id（依赖 {@code MemberSession}）；本服务已经没有验签能力，改为读网关注入的身份头
 * （{@link GatewayIdentityResolver}），解析不到就回落到客户端 IP——**限流绝不能因为
 * "拿不到身份"而放行**，那等于登录接口的计数桶可以被匿名流量绕过。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final CacheService cacheService;

    /** 身份解析：与 {@code @MemberId} 参数解析器**共用同一处实现**，避免两套口径 */
    private final GatewayIdentityResolver gatewayIdentityResolver;

    /** 限流总开关(测试可整体关闭) */
    @Value("${mall.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    /** 调试/测试用统一阈值覆盖(>0 时忽略注解上的 limit) */
    @Value("${mall.rate-limit.limit-override:-1}")
    private int limitOverride;

    /**
     * 是否信任 {@code X-Forwarded-For}/{@code X-Real-IP}（默认 false）。
     * 只有部署在可信反向代理之后才能打开，详见 {@link #clientIp(HttpServletRequest)}。
     */
    @Value("${mall.rate-limit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    @Around("@annotation(com.mall.usercenter.support.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RateLimit rl = method.getAnnotation(RateLimit.class);
        if (rl == null || !rateLimitEnabled) {
            return pjp.proceed();
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return pjp.proceed();   // 非 HTTP 上下文（定时任务/内部调用）不限流
        }
        String identity = rl.by() == RateLimit.By.USER ? resolveUser(request) : clientIp(request);
        String key = CacheKeys.rateLimit(rl.scope(), identity);
        long count = cacheService.increment(key, Duration.ofSeconds(rl.windowSeconds()));
        int limit = limitOverride > 0 ? limitOverride : rl.limit();
        if (count > limit) {
            log.warn("触发限流 scope={} identity={} count={}/{}", rl.scope(), identity, count, limit);
            throw new BusinessException(429, "操作过于频繁，请稍后再试");
        }
        return pjp.proceed();
    }

    /** 会员维度：优先用网关注入的身份；解析不到（匿名/凭据不可信）→ 回落 IP */
    private String resolveUser(HttpServletRequest request) {
        Long memberId = gatewayIdentityResolver.resolveOrNull(request);
        return memberId == null ? clientIp(request) : "u" + memberId;
    }

    /**
     * 限流身份（IP 维度）。
     *
     * <p>安全默认：**只信传输层地址** {@code request.getRemoteAddr()}。
     * `X-Forwarded-For` / `X-Real-IP` 是客户端可以随便伪造的头，直接采信等于把限流开关交给攻击者
     * （每个请求换一个假 IP 就是全新的计数桶，撞库/批量注册限流形同虚设）。
     *
     * <p>部署在 Nginx 之后时把 {@code mall.rate-limit.trust-forwarded-for=true} 打开，
     * 并确保前置代理是**覆盖**（而非追加）转发头。
     */
    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int idx = xff.indexOf(',');
                return idx > 0 ? xff.substring(0, idx).trim() : xff.trim();
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real;
            }
        }
        return request.getRemoteAddr();
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }
}
