package com.mall.review.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 我的评价条目。
 *
 * <p>逐字副本（单体 {@code com.mall.demo.pms.dto.MineCommentVO}）。
 *
 * <p><b>{@code spuTitle} 的来源在 P4 变了（ASSUMPTION，见 P4 批次 3 报告）</b>：
 * 改造前是"拿 spu_id 去 {@code pms_spu} 批量取<b>当前</b>标题"（商品改名后我的评价页跟着变），
 * 现在取 {@code review_pending_item.spu_title}——下单时的标题快照，且完全本地。
 * 代价是改名后不再跟随（历史快照语义，与昵称快照同一口径）；
 * 好处是这条读路径对本服务之外零依赖（P4 的目的）。
 * 读模型里没有这条明细（例如已被清理）时该字段为 {@code null}，与改造前"商品被删除"的表现一致。
 */
@Data
public class MineCommentVO {

    private Long id;
    private Long spuId;
    private String spuTitle;
    private Integer rating;
    private String content;
    private List<String> images;
    private LocalDateTime createTime;
}
