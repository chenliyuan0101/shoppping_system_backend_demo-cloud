package com.mall.trade.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 后台发货请求。
 */
@Data
@Schema(description = "发货请求")
public class ShipRequest {

    @Schema(description = "物流公司", example = "顺丰速运")
    @NotBlank(message = "请填写物流公司")
    private String logisticsCompany;

    @Schema(description = "物流单号", example = "SF1234567890")
    @NotBlank(message = "请填写物流单号")
    private String logisticsNo;
}
