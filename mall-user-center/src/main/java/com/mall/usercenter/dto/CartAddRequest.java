package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 加入购物车请求。
 */
@Data
@Schema(description = "加入购物车请求")
public class CartAddRequest {

    @Schema(description = "SKU ID", example = "2001")
    private Long skuId;

    @Schema(description = "数量(默认1)", example = "2")
    private Integer quantity;
}
