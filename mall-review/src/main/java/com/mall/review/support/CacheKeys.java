package com.mall.review.support;

/**
 * Redis key 规范（本服务自持副本，**只保留评价域真正会用到的条目**）。
 *
 * <p>裁剪原则同 P2/P3：不做"整个 CacheKeys 抄一遍"——抄进来的每一条都是
 * "看起来能用但其实没人写、也没人清理"的隐性债务。这里只留三类：
 * <ul>
 *   <li>{@link #PREFIX}：全局前缀，**跨服务逐字相同**（全站同一个 Redis 实例，
 *       前缀不一致会让运维按前缀排查/清理时漏掉本服务）；</li>
 *   <li>{@link #rateLimit(String, String)}：写路径（提交评价）的限流计数，
 *       命名与单体 {@code com.mall.demo.common.CacheKeys} 一致，
 *       这样 P4 切换时旧 key 的语义不变；</li>
 *   <li>{@link #spuCommentStat(long)}：本服务自有的"按 SPU 的评论数/好评率"缓存
 *       （方案 §4.5：商品详情页的 {@code goodRate} 计算整体搬到 review）。
 *       P4 第 1 批还没有写入方，先把 key 定了，避免下一批两个地方各起一个名字。</li>
 * </ul>
 *
 * <p>⚠️ 本服务**不持有** {@code mall:token:ver:*} / {@code mall:cache:member:status:*}：
 * 登录态与会员状态缓存是网关与 user-center 的约定（方案 §4.4），评价服务只消费
 * 网关注入的身份头，不读写这两个 key。
 */
public final class CacheKeys {

    public static final String PREFIX = "mall:";

    private CacheKeys() {
    }

    /** 提交评价的限流计数（按会员或 IP 维度），如 {@code mall:rl:comment:submit:1} */
    public static String rateLimit(String scope, String identity) {
        return PREFIX + "rl:" + scope + ":" + identity;
    }

    /** 某个 SPU 的评论统计缓存（数量/好评率，商品详情页用），如 {@code mall:cache:comment:stat:1001} */
    public static String spuCommentStat(long spuId) {
        return PREFIX + "cache:comment:stat:" + spuId;
    }
}
