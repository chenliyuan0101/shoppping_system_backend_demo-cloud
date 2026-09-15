package com.mall.common.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 缓存/计数的统一入口(本服务唯一出口)，与 user-center / review / 单体同一套实现。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>只用 StringRedisTemplate + JsonKit(Jackson 3)</b>：避开
 *       GenericJackson2JsonRedisSerializer 依赖 Jackson 2 的坑；</li>
 *   <li><b>失败降级(fail-open)</b>：Redis 不可用时不抛异常、不阻塞业务——读缓存返回 null(回落 DB)、
 *       <b>限流放行</b>（返回 -1）；并通过退避(30s)避免每次请求都去踩超时；</li>
 *   <li>可用 {@code mall.cache.enabled=false} 一键关闭全部 Redis 行为(保留纯 DB 逻辑)。</li>
 * </ol>
 *
 * <p><b>本批（P5-2）的真实调用点只有一个：限流</b>（{@link com.mall.marketing.config.RateLimitAspect}
 * 调 {@link #increment}）。get/set/delete 这些是"读缓存"那类能力，券中心列表与我的券在批次 3 之后
 * 才可能加缓存，现在放着是为了那两处不要各自手写一份 Redis 访问代码（同 P4 在 review 里的取舍）。
 *
 * <p>⚠️ {@link #increment} 的 TTL 只在**首次自增**时设置（{@code v == 1}）：
 * 固定窗口计数要求"窗口开始时定长、窗口内不续期"。若每次都 expire，计数窗口会被
 * 持续活跃的请求无限延长 —— 那不是限流，是把调用方永久关在门外。
 */
@Slf4j
public class CacheService {

    /** 降级退避窗口：Redis 失败后这段时间内不再尝试 */
    private static final long BACKOFF_MILLIS = 30_000L;

    private final ObjectProvider<StringRedisTemplate> templateProvider;

    @Value("${mall.cache.enabled:true}")
    private boolean enabled;

    private volatile long retryAfter = 0L;

    public CacheService(ObjectProvider<StringRedisTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    /** Redis 当前是否可用(用于限流等需明确判断的场景；不要用它做业务判断) */
    public boolean available() {
        return enabled && template() != null && System.currentTimeMillis() >= retryAfter;
    }

    // ==================== 基础操作 ====================

    public String get(String key) {
        StringRedisTemplate t = usableTemplate();
        if (t == null) {
            return null;
        }
        try {
            return t.opsForValue().get(key);
        } catch (Exception e) {
            degrade("get", key, e);
            return null;
        }
    }

    public void set(String key, String value, Duration ttl) {
        StringRedisTemplate t = usableTemplate();
        if (t == null) {
            return;
        }
        try {
            if (ttl == null) {
                t.opsForValue().set(key, value);
            } else {
                t.opsForValue().set(key, value, jitter(ttl));
            }
        } catch (Exception e) {
            degrade("set", key, e);
        }
    }

    /** 写入 JSON(对象 → JsonKit) */
    public void setJson(String key, Object value, Duration ttl) {
        set(key, JsonKit.toJson(value), ttl);
    }

    /** 读取 JSON(字符串 → 目标类型)，失败/不存在返回 null */
    public <T> T getJson(String key, Class<T> type) {
        String raw = get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonKit.toObject(raw, type);
        } catch (Exception e) {
            log.warn("缓存反序列化失败，忽略该 key: {} ({})", key, e.getMessage());
            delete(key);
            return null;
        }
    }

    /** 读取 JSON(泛型集合/分页)，失败/不存在返回 null */
    public <T> T getJson(String key, TypeReference<T> type) {
        String raw = get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonKit.toObject(raw, type);
        } catch (Exception e) {
            log.warn("缓存反序列化失败，忽略该 key: {} ({})", key, e.getMessage());
            delete(key);
            return null;
        }
    }

    public void delete(String... keys) {
        StringRedisTemplate t = usableTemplate();
        if (t == null || keys == null || keys.length == 0) {
            return;
        }
        try {
            for (String k : keys) {
                if (k != null) {
                    t.delete(k);
                }
            }
        } catch (Exception e) {
            degrade("delete", String.join(",", keys), e);
        }
    }

    /** 自增(用于限流计数)，返回自增后的值；Redis 不可用时返回 -1 表示"未计数"(调用方放行) */
    public long increment(String key, Duration ttl) {
        StringRedisTemplate t = usableTemplate();
        if (t == null) {
            return -1L;
        }
        try {
            Long v = t.opsForValue().increment(key);
            if (v != null && v == 1L && ttl != null) {
                t.expire(key, ttl);
            }
            return v == null ? -1L : v;
        } catch (Exception e) {
            degrade("increment", key, e);
            return -1L;
        }
    }

    /** SETNX：true=抢到；Redis 不可用时返回 true(放行，保证可用性) */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        StringRedisTemplate t = usableTemplate();
        if (t == null) {
            return true;
        }
        try {
            Boolean ok = t.opsForValue().setIfAbsent(key, value, ttl == null ? Duration.ofMinutes(5) : ttl);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            degrade("setIfAbsent", key, e);
            return true;
        }
    }

    // ==================== Redis Set（索引增量同步的兜底通道：mall:es:pending） ====================
    //
    // ⚠️ v5.2 共享内核合并时**必须保留这 3 个方法**：它们原先只在 product/search 的副本里
    //    （另外 6 个服务的副本没有），是"索引增量同步"的兜底通道。取共享版时按**并集**保留，
    //    否则 product/search 会以"找不到符号 setAdd"编译失败（第一版合并就踩了，见《微服务代码规范.md》§13.1）。

    /**
     * SADD：把成员加入集合，返回**新增**个数（已存在的成员不计数）。
     *
     * @return 新增个数；{@code -1} 表示 Redis 不可用（调用方据此记 warn 并走"全量重建筑底"）
     */
    public long setAdd(String key, String... members) {
        StringRedisTemplate t = usableTemplate();
        if (t == null || members == null || members.length == 0) {
            return -1L;
        }
        try {
            Long added = t.opsForSet().add(key, members);
            return added == null ? -1L : added;
        } catch (Exception e) {
            degrade("setAdd", key, e);
            return -1L;
        }
    }

    /**
     * SPOP：随机弹出至多 {@code count} 个成员（用于定时任务批量消费待同步队列）。
     *
     * @return 弹出的成员；Redis 不可用或参数非法时返回**空集合**（调用方按"本轮无待同步"处理）
     */
    public Set<String> setPop(String key, long count) {
        StringRedisTemplate t = usableTemplate();
        if (t == null || count <= 0) {
            return Set.of();
        }
        try {
            List<String> members = t.opsForSet().pop(key, count);
            return members == null ? Set.of() : new LinkedHashSet<>(members);
        } catch (Exception e) {
            degrade("setPop", key, e);
            return Set.of();
        }
    }

    /**
     * SCARD：集合大小（用于 {@code /internal/v1/search/status} 的"待同步条数"）。
     *
     * @return 集合大小；{@code -1} 表示 Redis 不可用
     */
    public long setSize(String key) {
        StringRedisTemplate t = usableTemplate();
        if (t == null) {
            return -1L;
        }
        try {
            Long size = t.opsForSet().size(key);
            return size == null ? -1L : size;
        } catch (Exception e) {
            degrade("setSize", key, e);
            return -1L;
        }
    }

    // ==================== 私有 ====================

    private StringRedisTemplate template() {
        return enabled ? templateProvider.getIfAvailable() : null;
    }

    private StringRedisTemplate usableTemplate() {
        if (!enabled) {
            return null;
        }
        if (System.currentTimeMillis() < retryAfter) {
            return null;   // 退避窗口内直接跳过，避免每次请求都等超时
        }
        return templateProvider.getIfAvailable();
    }

    private void degrade(String op, String key, Exception e) {
        retryAfter = System.currentTimeMillis() + BACKOFF_MILLIS;
        log.warn("Redis 不可用，已降级(fail-open) op={} key={} err={}", op, key, e.getMessage());
    }

    /** TTL 抖动 ±15%，防止同一时刻大批 key 同时过期(缓存雪崩) */
    private Duration jitter(Duration ttl) {
        long ms = ttl.toMillis();
        long delta = (long) (ms * 0.15);
        long jittered = ms + (delta <= 0 ? 0 : ThreadLocalRandom.current().nextLong(-delta, delta + 1));
        return Duration.ofMillis(Math.max(1000L, jittered));
    }
}
