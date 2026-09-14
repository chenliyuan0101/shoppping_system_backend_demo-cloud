package com.mall.demo.common;

import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Redis key 规范(统一前缀 mall:，集中定义避免散落字符串)。
 *
 * 约定：
 *   mall:cache:*  读缓存(可随时丢，短 TTL)
 *   mall:token:*  令牌版本号(主动失效)
 *   mall:rl:*     限流计数
 *   mall:idem:*   幂等键
 */
public final class CacheKeys {

    public static final String PREFIX = "mall:";

    private CacheKeys() {
    }

    // ---------- 读缓存 ----------
    // 注：P2 移除 MinIO 依赖后，原先在这里的 @Contract(pure = true)（org.jetbrains.annotations）
    //     也一并去掉——它只是 IDE 提示，来自 minio 的传递依赖；为了一个纯提示注解引入依赖不划算。
    public static @NonNull String homeIndex() {
        return PREFIX + "cache:home:index";
    }

    public static String categoryTree() {
        return PREFIX + "cache:category:tree";
    }

    public static String brandList() {
        return PREFIX + "cache:brand:list";
    }

    public static String productDetail(long spuId) {
        return PREFIX + "cache:product:detail:" + spuId;
    }

    /** 货架缓存版本号：后台增删改商品时 +1，旧货架缓存整体失效(无需遍历 key 删除) */
    public static String productShelfVersion() {
        return PREFIX + "cache:product:ver";
    }

    /** 货架分页缓存：key = 版本号 + 全部筛选参数摘要 */
    public static String productShelf(long version, String rawParams) {
        return PREFIX + "cache:product:shelf:v" + version + ":" + digest(rawParams);
    }

    /**
     * 成员状态缓存（P3 §4.4 ②）：{@code mall:cache:member:status:1}。
     *
     * <p>⚠️ **跨服务共有的 key**：网关/各服务只读它来判断"会员还在不在、禁没禁用"，
     * 写入方是用户中心（单体在过渡期也会回填）。字符串必须与
     * {@code mall-user-center} 的 {@code CacheKeys.memberStatus} 逐字一致。
     */
    public static String memberStatus(long memberId) {
        return PREFIX + "cache:member:status:" + memberId;
    }

    // ---------- 令牌版本 ----------
    public static String tokenVersion(String type, long userId) {
        return PREFIX + "token:ver:" + type + ":" + userId;
    }

    // ---------- 限流 ----------
    public static String rateLimit(String scope, String identity) {
        return PREFIX + "rl:" + scope + ":" + identity;
    }

    // ---------- 幂等 ----------
    public static String idemLock(long memberId, String requestId) {
        return PREFIX + "idem:order:lock:" + memberId + ":" + requestId;
    }

    public static String idemResult(long memberId, String requestId) {
        return PREFIX + "idem:order:result:" + memberId + ":" + requestId;
    }

    // ---------- ES 索引增量同步队列 ----------
    /** 待同步到 ES 的 spuId 集合(订单/售后链路只做 SADD，由定时任务 SPOP 消费) */
    public static String esPendingSync() {
        return PREFIX + "es:pending";
    }

    /** 参数摘要(避免 key 过长/含特殊字符) */
    private static String digest(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", out[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw == null ? 0 : raw.hashCode());
        }
    }
}
