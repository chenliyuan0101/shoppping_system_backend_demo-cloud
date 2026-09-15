package com.mall.common.autoconfig;

import com.mall.common.client.OutboundRestClientFactory;
import com.mall.common.support.CacheService;
import com.mall.common.web.TraceIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * `mall-common` 的自动装配（v5.2）。
 *
 * <h2>为什么用自动装配而不是让各服务扫 {@code com.mall.common}</h2>
 * 8 个服务的 {@code @SpringBootApplication} 只扫自己的 {@code com.mall.<域>} 包
 * （这是刻意的：扫描面越小，启动越可预测）。共享内核里那 3 个**需要成为 bean** 的类
 * （出站工厂 / 缓存 / traceId 过滤器）因此不能用 {@code @Component} 靠扫描生效，
 * 而是由本类显式声明——注册方式集中在**一处**，各服务零改动。
 *
 * <p>注册方式：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
 * （Boot 的自动装配清单；与 mall-trade 测试侧的测试替身清单同一机制）。
 *
 * <h2>与各服务原有副本的等价性</h2>
 * 三个 bean 的构造参数、属性名与默认值都与原副本**逐字一致**
 * （{@code mall.internal.token} / {@code mall.cache.enabled}），因此行为不变：
 * <ul>
 *   <li>{@link OutboundRestClientFactory}：正是各服务 {@code config} 包里那个 {@code @Component}；</li>
 *   <li>{@link CacheService}：原先各服务的 {@code @Service}（{@code mall.cache.enabled=false} 可一键关闭）；</li>
 *   <li>{@link TraceIdFilter}：原先各服务的 {@code @Component @Order(HIGHEST_PRECEDENCE)}，
 *       这里改成显式 {@link FilterRegistrationBean} 并把顺序钉在 {@link Ordered#HIGHEST_PRECEDENCE}
 *       —— 顺序必须早于所有其它过滤器/拦截器，MDC 才来得及给整行日志用上。</li>
 * </ul>
 * 三个 bean 都标了 {@link ConditionalOnMissingBean}：任何服务若自带同名 bean（排障/演练），
 * **以服务自己的为准**，共享内核自动让位。
 */
@AutoConfiguration
public class MallCommonAutoConfiguration {

    /** 出站 RestClient 的统一装配（{@code lb://} 用注入的 builder；{@code http://} 直连也挂同一个头拦截器） */
    @Bean
    @ConditionalOnMissingBean
    public OutboundRestClientFactory outboundRestClientFactory(
            @Value("${mall.internal.token:}") String internalToken) {
        return new OutboundRestClientFactory(internalToken);
    }

    /** Redis 门面（fail-open：Redis 不可用时退化成"没有缓存"，绝不让业务失败） */
    @Bean
    @ConditionalOnMissingBean
    public CacheService cacheService(ObjectProvider<StringRedisTemplate> templateProvider) {
        return new CacheService(templateProvider);
    }

    /**
     * 入站 traceId：把网关给的 {@code X-Trace-Id} 放进 MDC（形状闸门 + {@code finally} 清理）。
     *
     * <p>用 {@link FilterRegistrationBean} 而不是让类上挂 {@code @Order}：显式声明顺序，
     * 顺带避免"类上注解 + 自动装配"两处都能改顺序的歧义。
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }
}
