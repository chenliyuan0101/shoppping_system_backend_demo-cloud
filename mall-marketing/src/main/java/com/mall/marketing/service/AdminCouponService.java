package com.mall.marketing.service;

import com.mall.marketing.domain.Coupon;
import com.mall.marketing.dto.AdminCouponSaveRequest;
import com.mall.marketing.dto.CouponRecordVO;
import com.mall.marketing.support.PageResult;

/**
 * 后台券模板管理契约（券模板的建/改/启停/删 + 发放记录）。
 *
 * <p><b>P5 步骤 C</b>：本契约与它的实现在这一步从单体 {@code sms.service.AdminCouponService}
 * **整体搬进营销域**——后台规则的属主就是营销域（"券能不能删、已发放能不能改名"是券的业务规则，
 * 不是后台页面的规则）。单体只保留 `AdminCouponController` 的路径与签名，实现改为薄转发。
 *
 * <p>5 条后台校验文案与错误码逐字保留（对外契约）：
 * {@code 404 券模板不存在}、{@code 409 该券已有人领取，无法删除(可停用)}、
 * {@code 400 减免金额不能大于门槛金额}、{@code 400 暂仅支持满减券}、
 * {@code 400 固定时间段类型需填写开始时间}、{@code 400 时间格式错误，示例 yyyy-MM-ddTHH:mm:ss}，
 * 另有 DTO 级 {@code 400 请输入券名称 / 减免金额必须大于 0 / 门槛金额不能为负}。
 */
public interface AdminCouponService {

    /** 券模板分页（keyword 券名模糊、status 0启用1停用；页码/条数在属主域内收敛） */
    PageResult<Coupon> page(String keyword, Integer status, long pageNum, long pageSize);

    /** 新建券模板，返回新模板 id */
    Long create(AdminCouponSaveRequest request);

    /** 修改：**已发放的模板只允许改有效期**（名称/门槛/减免一律忽略，与改造前一致） */
    void update(Long id, AdminCouponSaveRequest request);

    /** 停用（status=1）：会员侧立即领不到（{@code 404 券不存在或已停发}） */
    void disable(Long id);

    /** 启用（status=0） */
    void enable(Long id);

    /** 删除：**已发放的不能删**（{@code 409 该券已有人领取，无法删除(可停用)}） */
    void delete(Long id);

    /** 发放记录（会员用户名/昵称来自 user-center 的批量契约；couponStatus 走 3→1 投影） */
    PageResult<CouponRecordVO> records(Long templateId, long pageNum, long pageSize);
}
