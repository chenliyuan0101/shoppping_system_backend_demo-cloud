package com.mall.marketing.config;

import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.CacheService;
import com.mall.marketing.support.RateLimit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>限流切面的纯单测</b>（不起 Spring 上下文、不连数据库）。
 *
 * <p>为什么限流除了"真库+真 Redis 的 HTTP 用例"还需要这一层：
 * <ul>
 *   <li>HTTP 用例证明"搬过来之后限流真的生效"，但它**依赖本机有 Redis**（没有就跳过）；</li>
 *   <li>这里的用例把 {@link CacheService} 换成 mock，因此可以**确定性地**验证三件事：
 *       ① 计数键逐字等于 {@code mall:rl:coupon_receive:u{memberId}}（与单体同一把桶）、
 *       ② 超限时抛的是 429 与原文案（且**不执行**业务方法）、
 *       ③ <b>Redis 不可用时 fail-open</b>（{@code increment} 返回 -1 → 放行）。
 *       第 ③ 条在活体上很难验（本机 Redis 在跑，而停掉共享 Redis 会影响别的服务），
 *       但它是"限流不会拖垮领券"的关键语义，必须有测试守住。</li>
 * </ul>
 */
class RateLimitAspectTest {

    private static final String GW_SECRET = "test-gateway-token";

    /** 被测切面真正会读到的方法签名来源：必须带 @RateLimit 且参数与 {@link CouponController#receive} 一致 */
    public static class FakeController {
        @RateLimit(scope = "coupon_receive", limit = 10, windowSeconds = 60, by = RateLimit.By.USER)
        public Object receive(Long memberId, Long templateId) {
            return "业务返回值";
        }
    }

    private CacheService cacheService;
    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        cacheService = mock(CacheService.class);
        // 身份解析用**真实实现**（不是 mock）：这样"凭据常量时间比较 + fail-closed"这条链路
        // 在限流用例里也被真的跑过一遍，而不是被 mock 掉。
        GatewayIdentityResolver resolver = new GatewayIdentityResolver(GW_SECRET);
        aspect = new RateLimitAspect(cacheService, resolver);
        ReflectionTestUtils.setField(aspect, "rateLimitEnabled", true);
        ReflectionTestUtils.setField(aspect, "limitOverride", -1);
        ReflectionTestUtils.setField(aspect, "trustForwardedFor", false);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private ProceedingJoinPoint joinPoint() throws Throwable {
        Method method = FakeController.class.getMethod("receive", Long.class, Long.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        // ⚠️ 必须用 doReturn：`when(pjp.proceed())` 会在打桩时**真的调用** proceed()，
        // 而它声明的是 throws Throwable（Mockito 的 when 不接受 checked Throwable）。
        doReturn("业务返回值").when(pjp).proceed();
        return pjp;
    }

    private void bindRequest(String gatewayAuth, String memberIdHeader, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/coupon/1/receive");
        if (gatewayAuth != null) {
            request.addHeader("X-Gateway-Auth", gatewayAuth);
        }
        if (memberIdHeader != null) {
            request.addHeader("X-Member-Id", memberIdHeader);
        }
        request.setRemoteAddr(remoteAddr);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("[限流] 计数键逐字是 mall:rl:coupon_receive:u{memberId}（与单体同一个桶）")
    void keyFormatMatchesMonolith() throws Throwable {
        bindRequest(GW_SECRET, "12345", "10.0.0.9");
        when(cacheService.increment(anyString(), any(Duration.class))).thenReturn(1L);

        Object result = aspect.around(joinPoint());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(cacheService).increment(key.capture(), ttl.capture());
        assertEquals("mall:rl:coupon_receive:u12345", key.getValue(),
                "键必须与单体 CacheKeys.rateLimit(scope, identity) 完全一致：切路由前后同一个计数桶");
        assertEquals(Duration.ofSeconds(60), ttl.getValue(), "窗口必须是注解上的 60 秒");
        assertEquals("业务返回值", result, "未超限时必须继续执行原方法");
    }

    @Test
    @DisplayName("[限流] 超限 → 抛 429「操作过于频繁，请稍后再试」，且**不执行**业务方法")
    void overLimitThrowsAndSkipsBusiness() throws Throwable {
        bindRequest(GW_SECRET, "12345", "10.0.0.9");
        when(cacheService.increment(anyString(), any(Duration.class))).thenReturn(11L);   // limit=10

        ProceedingJoinPoint pjp = joinPoint();
        BusinessException e = assertThrows(BusinessException.class, () -> aspect.around(pjp));

        assertEquals(429, e.getCode());
        assertEquals("操作过于频繁，请稍后再试", e.getMessage(), "文案必须逐字（C1）");
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("[限流] 恰好第 10 次不拦（边界：count > limit 才拦）")
    void exactlyAtLimitIsAllowed() throws Throwable {
        bindRequest(GW_SECRET, "12345", "10.0.0.9");
        when(cacheService.increment(anyString(), any(Duration.class))).thenReturn(10L);

        assertEquals("业务返回值", aspect.around(joinPoint()), "count == limit 应当放行");
    }

    @Test
    @DisplayName("[限流 fail-open] Redis 不可用（increment 返回 -1）→ 放行，领券不受 Redis 影响")
    void redisDownIsFailOpen() throws Throwable {
        bindRequest(GW_SECRET, "12345", "10.0.0.9");
        when(cacheService.increment(anyString(), any(Duration.class))).thenReturn(-1L);

        assertEquals("业务返回值", aspect.around(joinPoint()),
                "Redis 不可用时必须放行：限流故障不能变成'谁都领不了券'");
    }

    @Test
    @DisplayName("[限流] 身份不可信（凭据错/没有凭据）→ 回落到客户端 IP，绝不放行成'无身份'")
    void fallsBackToIpWhenIdentityUntrusted() throws Throwable {
        bindRequest("wrong-secret", "12345", "10.0.0.9");
        when(cacheService.increment(anyString(), any(Duration.class))).thenReturn(1L);

        aspect.around(joinPoint());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(cacheService).increment(key.capture(), any(Duration.class));
        assertEquals("mall:rl:coupon_receive:10.0.0.9", key.getValue(),
                "凭据不对时不能采信 X-Member-Id（否则伪造身份即可换桶绕过限流），必须回落 IP");
    }

    @Test
    @DisplayName("[限流] 不信任 X-Forwarded-For：伪造该头不会换桶")
    void ignoresForwardedForByDefault() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/coupon/1/receive");
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(cacheService.increment(anyString(), any(Duration.class))).thenReturn(1L);

        aspect.around(joinPoint());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(cacheService).increment(key.capture(), any(Duration.class));
        assertEquals("mall:rl:coupon_receive:10.0.0.9", key.getValue(),
                "XFF 是客户端可伪造的头：采信它等于每个请求换一个桶，限流形同虚设");
    }

    @Test
    @DisplayName("[限流] 非 HTTP 上下文（内部调用/定时任务）不限流，直接放行")
    void nonHttpContextIsSkipped() throws Throwable {
        RequestContextHolder.resetRequestAttributes();

        assertEquals("业务返回值", aspect.around(joinPoint()));
        verify(cacheService, never()).increment(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("[限流] 总开关关闭时完全不碰 Redis（排障/压测用）")
    void disabledSwitchBypassesCounting() throws Throwable {
        ReflectionTestUtils.setField(aspect, "rateLimitEnabled", false);
        bindRequest(GW_SECRET, "12345", "10.0.0.9");

        assertEquals("业务返回值", aspect.around(joinPoint()));
        verify(cacheService, never()).increment(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("[限流] limit-override>0 时按覆盖值拦（网关/单体同一套压测开关）")
    void limitOverrideWins() throws Throwable {
        ReflectionTestUtils.setField(aspect, "limitOverride", 2);
        bindRequest(GW_SECRET, "12345", "10.0.0.9");
        when(cacheService.increment(anyString(), any(Duration.class))).thenReturn(3L);

        BusinessException e = assertThrows(BusinessException.class, () -> aspect.around(joinPoint()));
        assertEquals(429, e.getCode());
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("频繁"), "文案仍是统一的限流文案");
    }
}
