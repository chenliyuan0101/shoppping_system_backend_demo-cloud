package com.mall.review.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 前台商品评价条目。
 *
 * <p>逐字副本（单体 {@code com.mall.demo.pms.dto.PublicCommentVO}）。字段名
 * {@code id/rating/content/images/nickname/createTime} 是对外契约。
 *
 * <p><b>{@code nickname} 的来源在 P4 变了，取值口径没变</b>：改造前是"拿 member_id 去会员域
 * 批量查昵称"，现在是本表的 {@code member_nickname} 历史快照（写评价时落库）。
 * 兜底仍是 {@code "匿名用户"}——快照为空（例如会员域当时不可用、提交时昵称落成了空串）时
 * 与改造前"查不到昵称"的表现完全一致。
 */
@Data
public class PublicCommentVO {

    private Long id;
    private Integer rating;
    private String content;
    private List<String> images;
    /** 会员昵称(脱敏可后续加强) */
    private String nickname;
    private LocalDateTime createTime;
}
