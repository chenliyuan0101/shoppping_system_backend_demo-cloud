package com.mall.trade.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 后台关闭订单请求。
 */
@Data
@Schema(description = "关闭订单请求")
public class CloseOrderRequest {

    @Schema(description = "关闭原因", example = "缺货无法发货")
    @NotBlank(message = "请填写关闭原因")
    private String reason;
}
