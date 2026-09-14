package com.mall.demo.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 幂等守卫(基于 Redis)：用于「重复提交/网络重试」会重复产生副作用的接口(如创建订单)。
 *
 * 语义：
 *  - 未传 requestId(如前端未带 Idempotency-Key) → 直接执行，等价于未启用幂等
 *  - 同一 (ownerId, requestId) 已完成 → **直接回放首次结果**，不重复执行
 *  - 同一 (ownerId, requestId) 正在执行中 → 抛 409，避免并发双开
 *  - Redis 不可用 → 放行(fail-open)，保证下单可用性优先
 *
 * key：mall:idem:...:lock(短锁，执行完即删) / mall:idem:...:result(24h 结果回放)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    /** 执行中锁的持有时间：足够覆盖一次下单事务，异常时也会立刻释放 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    /** 结果回放窗口：24h 内的同 key 重试都返回同一结果 */
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    private final CacheService cacheService;

    /**
     * 幂等执行：返回首次执行结果(重复调用返回缓存的结果)。
     *
     * @param ownerId   归属者(会员 id，避免不同用户串号)
     * @param requestId 幂等键(由前端生成，建议 UUID)
     * @param action    真正的业务动作
     */
    public String execute(long ownerId, String requestId, Supplier<String> action) {
        if (!StringUtils.hasText(requestId)) {
            return action.get();
        }
        String resultKey = CacheKeys.idemResult(ownerId, requestId);
        String done = cacheService.get(resultKey);
        if (StringUtils.hasText(done)) {
            log.info("命中幂等结果，直接回放 owner={} requestId={}", ownerId, requestId);
            return done;
        }
        String lockKey = CacheKeys.idemLock(ownerId, requestId);
        if (!cacheService.setIfAbsent(lockKey, "1", LOCK_TTL)) {
            throw new BusinessException(409, "请求正在处理中，请勿重复提交");
        }
        try {
            String result = action.get();
            cacheService.set(resultKey, result == null ? "" : result, RESULT_TTL);
            return result;
        } finally {
            // 执行成功/失败都释放锁：成功的重复请求由 resultKey 兜住，失败的允许重试
            cacheService.delete(lockKey);
        }
    }
}
