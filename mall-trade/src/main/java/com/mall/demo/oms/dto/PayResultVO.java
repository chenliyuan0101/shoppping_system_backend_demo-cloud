package com.mall.demo.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 支付结果(模拟支付后查询订单支付状态)。
 */
@Data
@Schema(description = "支付结果")
public class PayResultVO {

    @Schema(description = "订单号", example = "202609070000000001")
    private String orderNo;

    @Schema(description = "支付状态 0未支付 1已支付", example = "1")
    private Integer payStatus;
}
