package com.mall.marketing.config;

import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.CacheKeys;
import com.mall.marketing.support.CacheService;
import com.mall.marketing.support.RateLimit;
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
 * 计数 key = {@code mall:rl:{scope}:{identity}}；超限抛 429 业务异常(统一响应体)。
 * Redis 不可用或计数返回 -1 时**放行**(fail-open)。
 *
 * <p><b>与单体版本的唯一差别是"身份从哪来"</b>（本类逐字沿用 mall-user-center 的做法）：
 * 单体里 {@code RateLimit.By.USER} 维度要自己验签取会员 id（依赖 {@code MemberSession}）；
 * 本服务已经没有验签能力，改为读**网关注入的身份头**（{@link GatewayIdentityResolver}，
 * 常量时间比较凭据），解析不到就回落到客户端 IP——**限流绝不能因为"拿不到身份"而放行**，
 * 那等于领券的计数桶可以被匿名流量绕过。
 *
 * <p>⚠️ 身份解析与 {@code @MemberId} 参数解析器**共用同一个 {@link GatewayIdentityResolver}**：
 * 两处若各写一套"怎么算登录"，就会出现"参数解析认为是会员 A、限流记到 IP 桶"这种静默的不一致。
 *
 * <p>⚠️ 切面与参数解析的先后：Spring MVC **先解析方法参数、再调用（代理的）目标方法**，
 * 所以未登录请求会在 {@code @MemberId} 解析处抛 401，**早于**本切面的计数。
 * 这与单体改造前的顺序完全一致（那里 {@code @MemberId} 也要先走 {@code MemberSession}），
 * 因此"匿名请求不占计数桶"不是本批引入的行为变化。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final CacheService cacheService;

    /** 身份解析：与 {@code @MemberId} 参数解析器共用同一处实现，避免两套口径 */
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

    @Around("@annotation(com.mall.marketing.support.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RateLimit rl = method.getAnnotation(RateLimit.class);
        if (rl == null || !rateLimitEnabled) {
            return pjp.proceed();
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return pjp.proceed();   // 非 HTTP 上下文（内部调用/测试直调）不限流
        }
        String identity = rl.by() == RateLimit.By.USER ? resolveUser(request) : clientIp(request);
        // 键格式与单体逐字一致：mall:rl:{scope}:{identity}（切路由前后同一个计数桶）
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
     * {@code X-Forwarded-For} / {@code X-Real-IP} 是客户端可以随便伪造的头，直接采信等于把限流开关
     * 交给攻击者（每个请求换一个假 IP 就是全新的计数桶，批量领券限流形同虚设）。
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
