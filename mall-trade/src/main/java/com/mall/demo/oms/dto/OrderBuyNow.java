package com.mall.demo.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 立即购买的商品项。
 */
@Data
@Schema(description = "立即购买项")
public class OrderBuyNow {

    @Schema(description = "SKU ID", example = "2001")
    private Long skuId;

    @Schema(description = "数量", example = "1")
    private Integer quantity;
}
