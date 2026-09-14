package com.mall.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 轮播新增/修改请求。
 */
@Data
@Schema(description = "轮播新增/修改请求")
public class BannerSaveRequest {

    @Schema(description = "标题", example = "夏季大促")
    @NotBlank(message = "请输入轮播标题")
    private String title;

    @Schema(description = "图片 URL", example = "http://localhost:9000/mall/seed/banner1.jpg")
    @NotBlank(message = "请上传轮播图片")
    private String imageUrl;

    @Schema(description = "跳转链接(可空)", example = "/product/1001")
    private String linkUrl;

    @Schema(description = "排序(小在前)", example = "1")
    private Integer sort;

    @Schema(description = "状态 0停用 1启用(新增默认启用)", example = "1")
    private Integer status;
}
