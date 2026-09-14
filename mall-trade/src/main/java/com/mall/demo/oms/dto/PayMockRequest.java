package com.mall.demo.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 模拟支付请求。
 */
@Data
@Schema(description = "模拟支付请求")
public class PayMockRequest {

    @Schema(description = "订单号", example = "202609070000000001")
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @Schema(description = "true=支付成功 false=模拟支付失败(订单保持待支付)", example = "true")
    private Boolean success;
}
