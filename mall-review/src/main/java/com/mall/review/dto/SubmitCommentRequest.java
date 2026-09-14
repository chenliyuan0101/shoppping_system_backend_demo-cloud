package com.mall.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 发表评价请求：订单完成后可对全部或部分商品评价一次。
 *
 * <p><b>这是单体 {@code com.mall.demo.pms.dto.SubmitCommentRequest} 的逐字副本</b>
 * （P4 批次 3：评价的 HTTP 面整体搬到本服务）。拆服务不共享 jar，契约靠"逐字相同 + 契约测试"守。
 * 字段名、{@code @Schema} 文档、以及 {@code items} 上那句 {@code @NotEmpty(message = "请填写评价内容")}
 * 都是对外契约的一部分——那句话就是 C1 基线里的第 1 条错误文案（400，由
 * {@link com.mall.review.support.RequestValidator} 在 Service 开头触发）。
 */
@Data
@Schema(description = "发表评价请求")
public class SubmitCommentRequest {

    @Schema(description = "订单号", example = "202609070000000001")
    private String orderNo;

    @Schema(description = "评价条目(同一订单明细只能评一次)")
    @NotEmpty(message = "请填写评价内容")
    private List<CommentItem> items;

    @Data
    @Schema(description = "评价条目")
    public static class CommentItem {

        @Schema(description = "订单明细ID(见订单详情 items.orderItemId)", example = "1")
        private Long orderItemId;

        @Schema(description = "评分 1-5", example = "5")
        private Integer rating;

        @Schema(description = "评价内容", example = "很好用，音质出色")
        private String content;

        @Schema(description = "晒图 URL 数组(可选)", example = "[\"http://img/x.jpg\"]")
        private List<String> images;
    }
}
