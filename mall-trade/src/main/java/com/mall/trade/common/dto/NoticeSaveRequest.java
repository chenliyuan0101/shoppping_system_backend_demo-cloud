package com.mall.trade.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 公告新增/修改请求。P2 从 {@code cms.dto} 移到契约区，理由见 {@link BannerSaveRequest}。
 */
@Data
@Schema(description = "公告新增/修改请求")
public class NoticeSaveRequest {

    @Schema(description = "标题", example = "商城公告：满 199 减 30")
    @NotBlank(message = "请输入公告标题")
    private String title;

    @Schema(description = "内容", example = "全场满 199 减 30，欢迎选购")
    private String content;

    @Schema(description = "排序", example = "1")
    private Integer sort;

    @Schema(description = "状态 0停用 1启用", example = "1")
    private Integer status;
}
