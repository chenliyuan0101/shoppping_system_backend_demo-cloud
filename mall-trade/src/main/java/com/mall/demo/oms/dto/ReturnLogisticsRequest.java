package com.mall.demo.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 退货退款：用户填写寄回物流。
 */
@Data
@Schema(description = "回寄物流请求")
public class ReturnLogisticsRequest {

    @Schema(description = "物流公司", example = "中通快递")
    private String returnCompany;

    @Schema(description = "物流单号", example = "ZT1234567890")
    @NotBlank(message = "请填写退货物流单号")
    private String returnTrackingNo;
}
