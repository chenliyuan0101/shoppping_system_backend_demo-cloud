package com.mall.trade.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台新增/修改券模板的**请求形状**（单体 ↔ marketing 内部接口 + 单体对外的后台入参）。
 *
 * <p>P5 步骤 C：券后台规则的属主搬到营销域，本类作为**跨服务契约的形状**住在 {@code common.dto}
 * （原来的 {@code sms.dto} 会让 {@code common.client.MarketingClient} 依赖业务域 → 触 P0 规则 B6）。
 * 字段名与校验注解必须与 {@code mall-marketing} 的
 * {@code com.mall.marketing.dto.AdminCouponSaveRequest} **逐字相同**：
 * 校验文案是后台对外契约（{@code 请输入券名称}、{@code 减免金额必须大于 0}、{@code 门槛金额不能为负}），
 * 而**校验的执行方是营销域**（本层只做形状与转发）。
 */
@Data
public class AdminCouponSaveRequest {

    @NotBlank(message = "请输入券名称")
    private String name;

    /** 券类型 1满减券 */
    private Integer type;

    @NotNull(message = "减免金额必须大于 0")
    @Min(value = 1, message = "减免金额必须大于 0")
    private Long discountAmount;

    /** 使用门槛(分)，0=无门槛 */
    @NotNull(message = "门槛金额不能为负")
    @Min(value = 0, message = "门槛金额不能为负")
    private Long thresholdAmount;

    /** 发行总量(NULL=不限) */
    private Integer totalCount;

    /** 每人限领(默认1) */
    private Integer perMemberLimit;

    /** 有效期类型 1固定时间段 2领取后N天 */
    private Integer validType;

    /** 生效开始(validType=1)，格式 yyyy-MM-ddTHH:mm:ss */
    private String validStartTime;

    /** 生效结束(validType=1) */
    private String validEndTime;

    /** 领取后有效天数(validType=2) */
    private Integer validDays;
}
