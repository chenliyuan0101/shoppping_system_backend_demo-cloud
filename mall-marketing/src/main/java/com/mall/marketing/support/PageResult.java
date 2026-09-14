package com.mall.marketing.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页响应：{ total, pageNum, pageSize, list }(见《接口文档.md》1.5)。
 *
 * <p>本批（P5-1）没有分页接口——"我的券"（批次 2）与"后台发放记录"（批次 3）会用。
 * 字段名是契约：前端已经按 {@code total/pageNum/pageSize/list} 取值，
 * 现在放着不接线，比"用的时候再手抄一份"更不容易抄错字段名。
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
