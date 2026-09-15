package com.mall.trade.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 轮播新增/修改请求。
 *
 * <p>P2 从 {@code cms.dto} 移到共享内核的契约区：它现在同时是
 * "对外请求体"（{@code /api/admin/banner}）与"内部调用请求体"
 * （转发给 {@code mall-content} 的 {@code /internal/v1/content/banner}）。
 * 放在 {@code common.dto} 是 P0 就定下的规矩——**跨域（跨服务）的契约快照放共享内核**，
 * 否则 {@code common.client} 去 import 业务域的 DTO，会当场造出一条 B6 违规（闸门会拦）。
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
