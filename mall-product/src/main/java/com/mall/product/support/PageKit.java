package com.mall.product.support;

/**
 * 入参「钳到 [1, max] 区间」的统一工具。
 *
 * <p>最初是为分页写的：列表接口的 `pageNum/pageSize` 两个 long 直接参与
 * `LIMIT (page-1)*size, size` 计算，`pageSize` 各处都已 clamp，但 `pageNum` 没有上限——
 * 传一个极大值（如 {@code Long.MAX_VALUE}）时 `(page-1)*size` 会**溢出成负数**，
 * 拼出 `LIMIT -100,50`，MySQL 直接报 1064 语法错，最后被全局异常处理器转成 500"系统繁忙"。
 * 即使不溢出，超大 offset 也是无意义的深分页扫描。
 *
 * <p>后来同一种"给了就夹到合法区间、没给就用默认值"的写法也用在非分页场景
 * （看板取数天数、趋势图条数、死信重投条数），因此 {@link #size(long, long)} 的语义按
 * **通用钳制**理解即可，不必只当成分页专用。
 *
 * <p>约定：页码上限 10000（配合每页 ≤100，可覆盖 100 万条数据），超出按上限处理；
 * 需要"明确报错"的场景可以在 controller 侧自行校验。
 */
public final class PageKit {

    /** 允许的最大页码 */
    public static final long MAX_PAGE = 10_000L;

    private PageKit() {
    }

    /** 页码收敛：≥1 且 ≤ {@link #MAX_PAGE} */
    public static long page(long pageNum) {
        return Math.min(MAX_PAGE, Math.max(1L, pageNum));
    }

    /** 每页条数收敛：≥1 且 ≤ max */
    public static long size(long pageSize, long max) {
        return Math.min(max, Math.max(1L, pageSize));
    }

    /** offset：{@code (page-1)*size}，入参应为 {@link #page} / {@link #size} 收敛后的值 */
    public static long offset(long page, long size) {
        return (page - 1L) * size;
    }
}
