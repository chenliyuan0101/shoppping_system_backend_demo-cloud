package com.mall.product.support;

import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Redis key 规范(统一前缀 mall:，集中定义避免散落字符串)。
 *
 * 约定：
 *   mall:cache:*  读缓存(可随时丢，短 TTL)
 *
 * <p>⚠️ 本类只保留**商品域自己拥有**的 key（与 mall-review/mall-content/mall-marketing 的同一口径：
 * 每个服务自持副本、只留本域用得到的）。单体的 {@code CacheKeys} 里还有 {@code mall:token:*} /
 * {@code mall:rl:*} / {@code mall:idem:*} / {@code mall:cache:member:status:*}——那些属于
 * 认证/限流/交易/会员域，商品域一行都不读，因此**刻意不复制过来**（复制=留下"看起来在用"的死代码）。
 * 唯一跨域共享的是 {@link #esPendingSync()}：它是"待同步索引"的兜底队列，
 * 键名必须与单体（以及 P6-2 的 mall-search）**逐字相同**，否则两边的定时任务各看各的集合。
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
