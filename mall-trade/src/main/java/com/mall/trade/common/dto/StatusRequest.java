package com.mall.trade.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 通用启停/上下架请求体。
 */
@Data
@Schema(description = "状态变更请求")
public class StatusRequest {

    @Schema(description = "目标状态 0停用/下架 1启用/上架", example = "1")
    private Integer status;
}
