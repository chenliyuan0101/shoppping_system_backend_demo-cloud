package com.mall.content.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 缓存/计数的统一入口(全项目唯一出口)。
 *
 * 设计要点：
 *  1. **只用 StringRedisTemplate + JsonKit(Jackson 3)**：避开 GenericJackson2JsonRedisSerializer 依赖 Jackson 2 的坑
 *  2. **失败降级(fail-open)**：Redis 不可用时不抛异常、不阻塞业务——读缓存返回 null(回落 DB)、
 *     限流/幂等放行；并通过退避(30s)避免每次请求都去踩超时
 *  3. 可用 `mall.cache.enabled=false` 一键关闭全部 Redis 行为(保留纯 DB 逻辑)
 */
@Slf4j
@Service
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

    /** Redis 当前是否可用(用于限流/幂等等需明确判断的场景；不要用它做业务判断) */
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

    /** 自增(用于限流/版本号)，返回自增后的值；Redis 不可用时返回 -1 表示"未计数" */
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

    // ==================== Set(用作轻量队列，例如 ES 索引增量同步) ====================

    /** SADD：加入集合；Redis 不可用时返回 -1(调用方放弃标记，由全量重建筑底) */
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

    /** SPOP count：原子取出并移除(队列语义，取走即不再返回)；Redis 不可用时返回空集合 */
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

    /** 集合大小(自检/运维用)；Redis 不可用时返回 -1 */
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
