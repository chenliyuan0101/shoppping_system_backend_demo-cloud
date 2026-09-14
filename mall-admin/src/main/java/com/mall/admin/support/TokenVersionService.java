package com.mall.admin.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 令牌版本号(基于 Redis 的"无状态 JWT 主动失效"方案；自持副本，与单体/user-center 逐字同构)。
 *
 * 原理：签发 token 时把当前版本号写进 `ver` 声明；每次校验 token 时比对 Redis 中的版本号：
 *   - 管理员登出 / 被禁用 → 版本号 +1 → 所有旧 token 立即失效
 *   - Redis 不可用时降级：读版本返回 0、比对直接通过(fail-open)，行为与"未启用 Redis"一致
 *
 * 版本号 key：{@code mall:token:ver:admin:{id}}，TTL 30 天(到期自动回到 0，重新登录即可)。
 *
 * <p><b>P7 §2.5 的分工</b>：本服务是 `admin` 版本号的**写入方**（登出 / 启用→禁用跃迁时 bump）；
 * 网关只读它做比对。这是"禁用立即生效"的第二道保险（第一道是状态缓存里的 403 判据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenVersionService {

    private static final Duration TTL = Duration.ofDays(30);

    public static final String TYPE_USER = "user";
    public static final String TYPE_ADMIN = "admin";

    private final CacheService cacheService;

    /** 当前版本号(Redis 不可用 → 0) */
    public long current(String type, long userId) {
        String raw = cacheService.get(CacheKeys.tokenVersion(type, userId));
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 版本号 +1(登出/禁用时调用)，返回新版本号；Redis 不可用时返回 0（不抛异常） */
    public long bump(String type, long userId) {
        long v = cacheService.increment(CacheKeys.tokenVersion(type, userId), TTL);
        return v < 0 ? 0L : v;
    }

    /** 校验 token 里的版本号是否仍然有效(Redis 不可用 → 放行) */
    public boolean matches(String type, long userId, long tokenVer) {
        if (!cacheService.available()) {
            return true;   // 降级：不因 Redis 故障把管理员全部踢下线
        }
        return current(type, userId) == tokenVer;
    }
}
