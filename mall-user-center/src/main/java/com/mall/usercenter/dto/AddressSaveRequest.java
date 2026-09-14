package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 收货地址新增/修改请求。
 */
@Data
@Schema(description = "收货地址新增/修改请求", example = """
        {
          "receiverName": "张三",
          "receiverPhone": "13800138000",
          "provinceCode": "110000",
          "provinceName": "北京市",
          "cityCode": "110100",
          "cityName": "北京市",
          "districtCode": "110105",
          "districtName": "朝阳区",
          "detail": "建国路 88 号",
          "isDefault": true
        }
        """)
public class AddressSaveRequest {

    @Schema(description = "收货人姓名", example = "张三")
    @NotBlank(message = "请输入收货人姓名")
    private String receiverName;

    @Schema(description = "收货人手机号", example = "13800138000")
    @NotBlank(message = "收货人手机号格式不正确")
    @Pattern(regexp = "^1\\d{10}$", message = "收货人手机号格式不正确")
    private String receiverPhone;

    @Schema(description = "省代码", example = "110000")
    private String provinceCode;

    @Schema(description = "省名称", example = "北京市")
    private String provinceName;

    @Schema(description = "市代码", example = "110100")
    private String cityCode;

    @Schema(description = "市名称", example = "北京市")
    private String cityName;

    @Schema(description = "区代码", example = "110105")
    private String districtCode;

    @Schema(description = "区名称", example = "朝阳区")
    private String districtName;

    @Schema(description = "详细地址", example = "建国路 88 号")
    private String detail;

    @Schema(description = "是否设为默认", example = "true")
    private Boolean isDefault;
}
