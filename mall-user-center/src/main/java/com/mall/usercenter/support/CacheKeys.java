package com.mall.usercenter.support;

import com.mall.common.support.MemberId;

/**
 * Redis key 规范（本服务自持副本，只保留用户中心真正用到的条目）。
 *
 * <p><b>⚠️ 跨服务约定</b>：前两个 key 在 P3 期间是**网关与用户中心共有**的（同一个 Redis、同一个 key 名）：
 * <ul>
 *   <li>{@link #tokenVersion(String, long)}：网关用它做"令牌版本比对"（§4.4 ①），
 *       用户中心在登录/登出/改密/禁用时 bump 它（§4.4 ②）——**这是旧 token 立即失效的唯一机制**；</li>
 *   <li>{@link #memberStatus(long)}：成员状态缓存（{@code {memberId, nickname, status}}），
 *       网关与业务服务只读它，不查 {@code ums_member}。</li>
 * </ul>
 * 因此这两个字符串在两侧必须**逐字相同**，任何一侧改动都要同时改另一侧
 * （单体里对应 {@code com.mall.demo.common.CacheKeys}）。
 */
public final class CacheKeys {

    public static final String PREFIX = "mall:";

    private CacheKeys() {
    }

    // ---------- 令牌版本（跨服务共有，见类注释）----------

    /** 令牌版本号：{@code mall:token:ver:user:1} */
    public static String tokenVersion(String type, long userId) {
        return PREFIX + "token:ver:" + type + ":" + userId;
    }

    /** 会员状态缓存：{@code mall:cache:member:status:1} */
    public static String memberStatus(long memberId) {
        return PREFIX + "cache:member:status:" + memberId;
    }

    // ---------- 本服务自有 ----------

    /** 接口限流计数（登录/注册/改密等按 IP 或账号维度） */
    public static String rateLimit(String scope, String identity) {
        return PREFIX + "rl:" + scope + ":" + identity;
    }
}
