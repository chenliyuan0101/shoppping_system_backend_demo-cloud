package com.mall.search.support;

/**
 * Redis key 规范（**本服务只用一个 key**：`mall:es:pending`）。
 *
 * <p>与 mall-review / mall-content / mall-marketing / mall-product 的同一口径：
 * 每个服务自持副本、**只留自己用得到的**（复制别人的 key = 留下"看起来在用"的死代码）。
 *
 * <p>⚠️ 本服务唯一用到的 {@link #esPendingSync()} 是**跨服务共享**的：
 * 它是"待同步到 ES 的 spuId"的兜底队列，单体（过渡期）与 mall-search 都读写它，
 * 键名必须**逐字相同**，否则两边的定时任务各看各的集合、静默漏同步。
 */
public final class CacheKeys {

    public static final String PREFIX = "mall:";

    private CacheKeys() {
    }

    // ---------- ES 索引增量同步队列 ----------
    /** 待同步到 ES 的 spuId 集合(订单/售后链路只做 SADD，由定时任务 SPOP 消费) */
    public static String esPendingSync() {
        return PREFIX + "es:pending";
    }
}
