package com.mall.trade.app;

import com.mall.trade.auth.support.MemberSession;
import com.mall.trade.common.BusinessException;
import com.mall.trade.common.CacheKeys;
import com.mall.common.support.CacheService;
import com.mall.trade.common.RateLimit;
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
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final CacheService cacheService;
    private final MemberSession memberSession;

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

    @Around("@annotation(com.mall.trade.common.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RateLimit rl = method.getAnnotation(RateLimit.class);
        if (rl == null || !rateLimitEnabled) {
            return pjp.proceed();
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return pjp.proceed();
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

    private String resolveUser(HttpServletRequest request) {
        try {
            return "u" + memberSession.requireUserId(request.getHeader("Authorization"));
        } catch (Exception e) {
            return clientIp(request);   // 未登录/解析失败 → 回落 IP
        }
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
