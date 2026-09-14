package com.mall.review.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页响应：{ total, pageNum, pageSize, list }(见《接口文档.md》1.5)。
 *
 * <p>本批还没有分页接口（评价列表在 P4 后续批次搬过来），先放进来是因为
 * 它是共享内核里被评价列表/管理端评价管理大量复用的契约形状；
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
