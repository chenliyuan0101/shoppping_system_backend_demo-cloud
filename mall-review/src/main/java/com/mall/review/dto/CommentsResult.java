package com.mall.review.dto;

import com.mall.review.support.PageResult;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 商品评价分页结果(含好评率)。
 *
 * <p>逐字副本（字段名 {@code goodRate} / {@code comments} 是对外契约，前端
 * {@code ProductDetail.vue} 直接读 {@code data.goodRate} 与 {@code data.comments.list}）。
 *
 * <p>{@code goodRate} 的口径与改造前一致：<b>4-5 星占全部展示中评价的百分比整数</b>，
 * 且"一条评价都没有"时是 {@code 100}（不是 0）——这是改造前就有的口径，P4 不修正它
 * （C1：对外数值不变）。
 */
@Data
@AllArgsConstructor
public class CommentsResult {

    /** 好评率(4-5 星占比，百分比整数) */
    private Integer goodRate;

    private PageResult<PublicCommentVO> comments;
}
