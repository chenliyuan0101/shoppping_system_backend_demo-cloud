package com.mall.marketing.support;

/**
 * Redis key 规范（本服务自持副本，**只保留营销域真正会用到的条目**）。
 *
 * <p>裁剪原则同 P2/P3/P4：不做"整个 CacheKeys 抄一遍"——抄进来的每一条都是
 * "看起来能用但其实没人写、也没人清理"的隐性债务。这里只留两条：
 * <ul>
 *   <li>{@link #PREFIX}：全局前缀，**跨服务逐字相同**（全站同一个 Redis 实例，
 *       前缀不一致会让运维按前缀排查/清理时漏掉本服务）；</li>
 *   <li>{@link #rateLimit(String, String)}：接口限流计数。它的**格式必须与单体
 *       {@code com.mall.demo.common.CacheKeys.rateLimit} 逐字一致**——
 *       领券端点在 P5 批次 2 从单体搬到本服务，同一个 scope 同一个 key，
 *       切路由前后**是同一个计数桶**（否则切换那一刻计数清零 = 给刷券留了一个窗口；
 *       运维按 {@code mall:rl:coupon_receive:*} 查限流也要两边都能查到）。</li>
 * </ul>
 *
 * <p>⚠️ 本服务**不持有** {@code mall:token:ver:*} / {@code mall:cache:member:status:*}：
 * 登录态与会员状态缓存是网关与 user-center 的约定（方案 §4.4），营销服务只消费
 * 网关注入的身份头（见 {@code GatewayIdentityResolver}），不读写这两个 key。
 */
public final class CacheKeys {

    public static final String PREFIX = "mall:";

    private CacheKeys() {
    }

    /** 接口限流计数（券域目前只有领券 {@code coupon_receive}，按会员维度），如 {@code mall:rl:coupon_receive:u1} */
    public static String rateLimit(String scope, String identity) {
        return PREFIX + "rl:" + scope + ":" + identity;
    }
}
