package com.mall.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新增类目请求。
 */
@Data
@Schema(description = "新增类目请求")
public class CategorySaveRequest {

    @Schema(description = "父类目ID，0=一级类目", example = "0")
    private Long parentId;

    @Schema(description = "类目名称", example = "家用电器")
    @NotBlank(message = "请输入类目名称")
    private String name;

    @Schema(description = "排序(小在前)", example = "3")
    private Integer sort;
}
