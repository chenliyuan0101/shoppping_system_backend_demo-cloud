package com.mall.admin.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页响应：{@code { total, pageNum, pageSize, list }}（《接口文档.md》1.5）。
 *
 * <p><b>C1：逐字对齐单体 {@code com.mall.demo.common.PageResult}</b>——字段名与顺序一致，
 * 因为它就是 {@code GET /api/admin/member/page} 的 {@code data} 形状
 * （后台会员列表的分页主查结果从 user-center 原样搬过来，只换 list 的元素类型）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private long total;
    private long pageNum;
    private long pageSize;
    private List<T> list;

    public static <T> PageResult<T> of(long total, long pageNum, long pageSize, List<T> list) {
        return new PageResult<>(total, pageNum, pageSize, list);
    }
}
