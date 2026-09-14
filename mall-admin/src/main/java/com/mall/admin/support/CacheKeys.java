package com.mall.admin.support;

/**
 * Redis key 规范（本服务自持副本，只保留管理端真正用到的条目）。
 *
 * <p><b>⚠️ 跨服务约定（P7 §2.5 / `P7-gateway-admin-auth-report.md` §2.1）</b>：
 * 前两个 key 是**网关与 mall-admin 共有**的（同一个 Redis、同一个 key 名，逐字相同）：
 * <ul>
 *   <li>{@link #adminStatus(long)}：网关**只读**它来产出「403 账号已被禁用」；
 *       写入方**只有本服务**（登录/改状态/定时对账）；</li>
 *   <li>{@link #tokenVersion(String, long)}：网关用它做令牌版本比对；
 *       本服务在登出/禁用时 bump 它（INCR，TTL 30 天）。</li>
 * </ul>
 * 任何一侧改动 key 名都要同时改另一侧 —— 写错一个字符的表现是
 * "403 永远不出现（网关读不到）"或"所有管理员被误判失效"，而且**不会有编译错误**。
 *
 * <p>后半部分（{@link #rateLimit} / {@link #dashboardGeneration} / {@link #dashboardEntry}）
 * 是**本服务自有**的键，没有跨服务读者，改动只需看本仓库。
 */
public final class CacheKeys {

    public static final String PREFIX = "mall:";

    private CacheKeys() {
    }

    // ---------- 跨服务共有（网关只读，本服务是唯一写入方，见类注释）----------

    /** 令牌版本号：{@code mall:token:ver:admin:1} */
    public static String tokenVersion(String type, long userId) {
        return PREFIX + "token:ver:" + type + ":" + userId;
    }

    /** 管理员状态缓存：{@code mall:cache:admin:status:1}（P7 §2.5） */
    public static String adminStatus(long adminId) {
        return PREFIX + "cache:admin:status:" + adminId;
    }

    // ---------- 本服务自有 ----------

    /** 接口限流计数（后台登录按 IP/身份维度） */
    public static String rateLimit(String scope, String identity) {
        return PREFIX + "rl:" + scope + ":" + identity;
    }

    /**
     * 看板缓存**代**（P7 §3 的"主动失效"落点）：{@code mall:cache:admin:dashboard:gen}。
     *
     * <p>为什么不直接删 key：看板缓存键里带参数（{@code trend:7}、{@code top:sales:5}…），
     * 失效时无法枚举（`CacheService` 也没有 {@code SCAN}/{@code KEYS}——那是生产禁忌）。
     * 用"代 + INCR"是标准做法：换代 ⇒ 旧代的键**立刻不可达**（TTL 到期自然消失），
     * 而 INCR 是 O(1)、无枚举、无锁。
     */
    public static String dashboardGeneration() {
        return PREFIX + "cache:admin:dashboard:gen";
    }

    /**
     * 看板缓存条目：{@code mall:cache:admin:dashboard:v{generation}:{suffix}}。
     *
     * <p>把"代"放在**前缀**位置（{@code v3:summary} / {@code v3:trend:7}）：
     * 排查时一眼能看出某个键属于哪一代，且 {@code KEYS mall:cache:admin:dashboard:v3:*} 这种
     * 人工排障写法是对的（生产里不许跑，但脑子里要能想清楚）。
     */
    public static String dashboardEntry(long generation, String suffix) {
        return PREFIX + "cache:admin:dashboard:v" + generation + ":" + suffix;
    }
}
