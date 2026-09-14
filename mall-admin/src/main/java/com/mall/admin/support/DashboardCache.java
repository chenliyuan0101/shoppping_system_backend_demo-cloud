package com.mall.admin.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;

/**
 * 看板缓存（P7 §3）：短 TTL（分钟级）+ <b>主动失效</b>。
 *
 * <h2>为什么看板要缓存</h2>
 * 三个端点每次都要问 trade/product/user 三个域，而看板是"进后台首页就看一次"的读路径——
 * 它不需要秒级新鲜度（业务上"今日销售额"早 60 秒没有意义），却要承担三倍的下游压力。
 * 规格给的方案就是这个：**短 TTL + 主动失效**。
 *
 * <h2>键的形状：{@code v{代}:{后缀}}（"代"= generation，见 {@link CacheKeys#dashboardGeneration()}）</h2>
 * 后缀是"端点 + 影响结果的参数"：
 * <pre>
 *   summary                        —— 无参数
 *   trend:{days}
 *   top:{type}:{limit}             —— type 已归一成 sales/amount
 * </pre>
 *
 * <h2>主动失效为什么用 INCR 而不是删键</h2>
 * 要删的键是"所有参数组合"（{@code trend:1..30} × {@code top:2×1..20}），既枚举不完，
 * 也不该在生产上跑 {@code KEYS}。所以缓存键里带一个"代"，失效 = {@code INCR 代}：
 * 旧代的键立刻不可达（TTL 到期自然回收）。这与项目里"令牌版本号 bump ⇒ 旧令牌立刻失效"
 * 是同一个手法（{@link TokenVersionService}）——**同一个仓库里不要有两种失效风格**。
 *
 * <h2>🔴 两条硬纪律</h2>
 * <ol>
 *   <li><b>降级结果绝不入缓存</b>：把"trade 挂了 ⇒ 四个字段全 0"缓存 60 秒，等于让一次
 *       瞬时故障变成一个分钟的对外错误结论（而且是**静默**的：形状完全正常）。
 *       写入由调用方用"本次是否发生降级"把关（见 AdminDashboardServiceImpl）。</li>
 *   <li><b>Redis 不可用时全部 fail-open</b>：读不到 ⇒ 回落实时聚合，写不进 ⇒ 下次再写。
 *       {@link CacheService} 自带 30s 退避，不会每个请求都去踩超时。</li>
 * </ol>
 *
 * <h2>TTL</h2>
 * 默认 60 秒（{@code mall.admin.dashboard.cache-ttl-seconds}）；{@link CacheService#set} 会给
 * TTL 加 ±15% 抖动（防雪崩），所以实际 TTL 落在 51~69 秒——用例断言的是这个区间，不是字面 60。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardCache {

    /** "代"键的 TTL：与令牌版本号同口径（30 天）。它只是个计数器，长 TTL 避免过期后回落到 0 代。 */
    private static final Duration GENERATION_TTL = Duration.ofDays(30);

    private final CacheService cacheService;

    @Value("${mall.admin.dashboard.cache-enabled:true}")
    private boolean enabled;

    /** 缓存条目 TTL（秒）——**分钟级**，见类注释 */
    @Value("${mall.admin.dashboard.cache-ttl-seconds:60}")
    private long ttlSeconds;

    public boolean enabled() {
        return enabled;
    }

    public Duration ttl() {
        return Duration.ofSeconds(Math.max(1L, ttlSeconds));
    }

    /** 当前"代"。Redis 不可用 / 键不存在 ⇒ 0（键不存在与代 0 是同一件事，第一次 INCR 得到 1） */
    public long generation() {
        String raw = cacheService.get(CacheKeys.dashboardGeneration());
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("看板缓存代号不可解析，按 0 处理: raw={}", raw);
            return 0L;
        }
    }

    /** 当前代的条目键（读、写、用例断言都用它，保证"算键的地方只有一处"） */
    public String key(String suffix) {
        return CacheKeys.dashboardEntry(generation(), suffix);
    }

    /** 主动失效：换代。返回新代号（Redis 不可用时返回 -1，此时缓存本来就不可用） */
    public long evictAll() {
        long gen = cacheService.increment(CacheKeys.dashboardGeneration(), GENERATION_TTL);
        log.info("看板缓存已主动失效（换代）: newGeneration={}", gen);
        return gen;
    }

    /** 读条目；未命中/反序列化失败 ⇒ null */
    public <T> T get(String key, Class<T> type) {
        return enabled ? cacheService.getJson(key, type) : null;
    }

    /** 读列表型条目（trend/top）；未命中 ⇒ null */
    public <T> T get(String key, TypeReference<T> type) {
        return enabled ? cacheService.getJson(key, type) : null;
    }

    /** 写条目。⚠️ 调用方必须**只在没有降级时**调用它（见类注释第 1 条纪律） */
    public void put(String key, Object value) {
        if (!enabled) {
            return;
        }
        cacheService.setJson(key, value, ttl());
    }
}
