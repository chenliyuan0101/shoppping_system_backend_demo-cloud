package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 收货地址展示对象(字段与 ums_address 实体对外字段一一对应，字段名供前台 CheckoutView/Profile 直接读取)。
 */
@Data
@Schema(description = "收货地址")
public class AddressVO {

    @Schema(description = "地址ID", example = "1")
    private Long id;

    @Schema(description = "所属会员ID", example = "1")
    private Long memberId;

    @Schema(description = "收货人姓名", example = "张三")
    private String receiverName;

    @Schema(description = "收货人手机号", example = "13800138000")
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

    /** 是否默认 0否 1是 */
    @Schema(description = "是否默认 0否 1是", example = "1")
    private Integer isDefault;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
