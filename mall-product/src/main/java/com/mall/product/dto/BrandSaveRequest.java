package com.mall.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 品牌新增/更新请求。
 */
@Data
@Schema(description = "品牌新增/更新请求")
public class BrandSaveRequest {

    @Schema(description = "品牌名称", example = "华为")
    private String name;

    @Schema(description = "品牌 Logo URL(可空)", example = "http://localhost:9000/mall/seed/logo-huawei.png")
    private String logo;

    @Schema(description = "排序(小在前)", example = "1")
    private Integer sort;

    @Schema(description = "状态 0停用 1启用(更新时可选)", example = "1")
    private Integer status;
}
