package com.mall.content.support;

/**
 * Redis key 规范（本服务自持副本，只保留内容域真正用到的条目）。
 *
 * <p><b>⚠️ 跨服务约定</b>：{@link #homeIndex()} 这个 key 在 P2 期间由**内容域与商品域共用**
 * （同一个 Redis、同一个 key 名）：
 * <ul>
 *   <li>内容域：写 Banner/公告后删除它（前台立即看到最新内容）；</li>
 *   <li>商品域：后台增删改商品后也删除它（见单体的 {@code AdminProductServiceImpl}）。</li>
 * </ul>
 * 因此这个字符串在两侧必须**逐字相同**（{@code mall:cache:home:index}），
 * 任何一侧改动都要同时改另一侧——这是《微服务改造方案.md》§2.9 第 5 条记录的不变量。
 * 内容域将来若换独立 Redis，则改为由 {@code product.changed} 事件驱动失效（已记入 P6 待办）。
 */
public final class CacheKeys {

    public static final String PREFIX = "mall:";

    private CacheKeys() {
    }

    /** 首页聚合缓存（跨服务共有 key，见类注释） */
    public static String homeIndex() {
        return PREFIX + "cache:home:index";
    }
}
