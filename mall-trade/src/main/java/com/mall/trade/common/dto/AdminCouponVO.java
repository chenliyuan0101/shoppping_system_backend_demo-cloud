package com.mall.trade.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 券模板的**后台响应形状**（{@code GET /api/admin/coupon/page} 的 list 元素）。
 *
 * <p><b>P5 步骤 C 之后券的属主是 {@code mall-marketing}</b>，单体不再有任何券表读写
 * （mapper 与表扫描包都已移除，{@code grep} 单体源码里已经找不到券表名）。
 * 之所以还在单体保留这个类，是因为**后台接口的响应 JSON 形状是对外契约**：
 * 字段名/字段集一个字都不能变，而薄转发的 controller 需要给 Jackson 与 springdoc 一个具体类型。
 *
 * <h2>为什么住在 {@code common.dto} 而不是原来的 {@code sms.domain}</h2>
 * 它现在是**跨服务契约的形状**（单体 ↔ marketing 的内部后台接口 + 单体对外的后台响应都用它），
 * 与 {@link CouponBriefVO}、{@link MemberBriefVO} 同一类东西。
 * 放在 {@code sms.domain} 会让 {@code common.client.MarketingClient} 依赖业务域，
 * 直接踩 P0 的边界规则 B6（实测：闸门报 3 条违规，正是这三个形状）。
 *
 * <p>字段必须与 {@code mall-marketing} 的 {@code com.mall.marketing.domain.Coupon}
 * **逐字相同**（那份才是真实体）——两侧靠"逐字相同 + 契约测试"守，不建共享 jar。
 */
@Data
public class AdminCouponVO {

    private Long id;

    private String name;

    /** 1满减券(直减) */
    private Integer type;

    /** 门槛(分，0=无门槛) */
    private Long thresholdAmount;

    /** 减免金额(分) */
    private Long discountAmount;

    /** 发行量 NULL=不限 */
    private Integer totalCount;

    private Integer receivedCount;

    private Integer perMemberLimit;

    /** 1固定时间段 2领取后N天 */
    private Integer validType;

    private LocalDateTime validStartTime;

    private LocalDateTime validEndTime;

    private Integer validDays;

    /** 0启用 1停用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
