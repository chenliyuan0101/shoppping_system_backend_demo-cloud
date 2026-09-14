package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 修改购物车条目数量请求。
 */
@Data
@Schema(description = "修改数量请求")
public class CartQuantityRequest {

    @Schema(description = "目标数量(1~min(99,库存))", example = "3")
    private Integer quantity;
}
