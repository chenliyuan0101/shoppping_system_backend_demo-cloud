package com.mall.marketing.internal;

import com.mall.marketing.domain.Coupon;
import com.mall.marketing.dto.AdminCouponPageRequest;
import com.mall.marketing.dto.AdminCouponRecordsRequest;
import com.mall.marketing.dto.AdminCouponSaveRequest;
import com.mall.marketing.dto.CouponRecordVO;
import com.mall.marketing.service.AdminCouponService;
import com.mall.marketing.support.ApiResponse;
import com.mall.common.support.PageKit;
import com.mall.marketing.support.PageResult;
import com.mall.marketing.support.RequestValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台券管理的**内部**接口（P5 步骤 C）：由单体的 {@code AdminCouponController} 薄转发而来。
 *
 * <p>{@code /internal/**} 由 {@code InternalApiAuthInterceptor} 守（没有 {@code X-Internal-Token} 一律 403）。
 *
 * <h2>为什么后台端点是"单体薄转发"而不是网关直路由</h2>
 * 管理端鉴权（{@code sys_user} + {@code AdminAuthInterceptor}）还在单体，属 P7 的 BFF；
 * 与 P2 内容域同一口径：**鉴权留在单体，业务落营销域**。
 * 如果把 {@code /api/admin/coupon/**} 也路由到本服务，它就会在没有管理端鉴权的情况下对外裸奔
 * ——这正是本步**刻意不**把管理端路径加进网关谓词的原因（谓词只有 {@code /api/coupon,/api/coupon/**}）。
 *
 * <h2>形状与单体的对应关系（逐字对齐，调用方只改地址不改语义）</h2>
 * <table border="1"><caption>映射</caption>
 *   <tr><th>单体（对外）</th><th>本服务（内部）</th></tr>
 *   <tr><td>{@code GET /api/admin/coupon/page?keyword&status&pageNum&pageSize}</td>
 *       <td>{@code POST /page}（body {@code {keyword,status,pageNum,pageSize}}）</td></tr>
 *   <tr><td>{@code POST /api/admin/coupon}</td><td>{@code POST /create}</td></tr>
 *   <tr><td>{@code PUT /api/admin/coupon/{id}}</td><td>{@code POST /{id}/update}</td></tr>
 *   <tr><td>{@code PUT /api/admin/coupon/{id}/enable|disable}</td>
 *       <td>{@code POST /{id}/enable|disable}</td></tr>
 *   <tr><td>{@code DELETE /api/admin/coupon/{id}}</td><td>{@code POST /{id}/delete}</td></tr>
 *   <tr><td>{@code GET /api/admin/coupon/{id}/records?pageNum&pageSize</td>
 *       <td>{@code POST /{id}/records}</td></tr>
 * </table>
 * 内部面统一用 POST + JSON：这些动作是**写**（或带业务参数），GET 会被中间层/网关重试成重复写；
 * 且 {@code pageNum/pageSize} 放进 body 后参数收敛只在本域内做一次（{@link PageKit}）。
 *
 * <h2>响应</h2>
 * 一律 {@code {code,message,data}}、HTTP 恒 200（见 {@code ApiResponse}）。
 * ⚠️ 调用方（单体）必须**按 code 判成败并原样透传文案**：{@code 409 该券已有人领取，无法删除(可停用)}
 * 这类业务错误不能被包成 500，否则后台页面会从"这条券删不了"变成"系统繁忙"。
 */
@RestController
@RequestMapping("/internal/v1/marketing/admin/coupon")
@RequiredArgsConstructor
public class InternalMarketingAdminCouponController {

    private final AdminCouponService adminCouponService;

    private final RequestValidator requestValidator;

    /** 券模板分页（返回**券模板实体**，JSON 字段名与单体后台逐字一致） */
    @PostMapping("/page")
    public ApiResponse<PageResult<Coupon>> page(@RequestBody AdminCouponPageRequest request) {
        requestValidator.check(request);
        return ApiResponse.ok(adminCouponService.page(request.getKeyword(), request.getStatus(),
                orDefault(request.getPageNum(), 1L), orDefault(request.getPageSize(), 10L)));
    }

    /** 新建券模板 → 返回新模板 id（单体后台的 400/409 文案由本域抛，见 AdminCouponService） */
    @PostMapping("/create")
    public ApiResponse<Long> create(@RequestBody AdminCouponSaveRequest request) {
        return ApiResponse.ok(adminCouponService.create(request));
    }

    /** 修改（已发放的模板只允许改有效期） */
    @PostMapping("/{id}/update")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody AdminCouponSaveRequest request) {
        adminCouponService.update(id, request);
        return ApiResponse.ok();
    }

    /** 停用 */
    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        adminCouponService.disable(id);
        return ApiResponse.ok();
    }

    /** 启用（恢复发券） */
    @PostMapping("/{id}/enable")
    public ApiResponse<Void> enable(@PathVariable Long id) {
        adminCouponService.enable(id);
        return ApiResponse.ok();
    }

    /** 删除（仅未发放可删；已发放 → 409「该券已有人领取，无法删除(可停用)」） */
    @PostMapping("/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        adminCouponService.delete(id);
        return ApiResponse.ok();
    }

    /** 发放记录（会员用户名/昵称来自 user-center 批量契约；couponStatus 走 3→1 投影） */
    @PostMapping("/{id}/records")
    public ApiResponse<PageResult<CouponRecordVO>> records(@PathVariable Long id,
                                                           @RequestBody(required = false)
                                                           AdminCouponRecordsRequest request) {
        long pageNum = request == null ? 1L : orDefault(request.getPageNum(), 1L);
        long pageSize = request == null ? 10L : orDefault(request.getPageSize(), 10L);
        return ApiResponse.ok(adminCouponService.records(id, pageNum, pageSize));
    }

    /** 单体后台的 {@code PageQuery} 默认值口径：未传即第 1 页、每页 10 条 */
    private static long orDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }
}
