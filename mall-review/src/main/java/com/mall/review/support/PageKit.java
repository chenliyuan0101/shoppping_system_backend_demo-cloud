package com.mall.review.support;

/**
 * 入参「钳到 [1, max] 区间」的统一工具。
 *
 * <p>为什么必须有它：{@code pageNum/pageSize} 直接参与 {@code LIMIT (page-1)*size, size} 计算，
 * 传一个极大值（如 {@code Long.MAX_VALUE}）时 {@code (page-1)*size} 会**溢出成负数**，
 * 拼出 {@code LIMIT -100,50}，MySQL 报 1064 语法错，最后被全局异常处理器转成
 * 500"系统繁忙"——客户端明明是自己传错了。即便不溢出，超大 offset 也是无意义的深分页扫描。
 *
 * <p>{@link #size(long, long)} 的语义是**通用钳制**（"给了就夹到合法区间、没给就用默认值"），
 * 不必只当成分页专用。
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
