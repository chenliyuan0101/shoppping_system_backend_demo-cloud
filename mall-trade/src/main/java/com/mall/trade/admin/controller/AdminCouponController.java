package com.mall.trade.admin.controller;

import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.PageQuery;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.client.MarketingClient;
import com.mall.trade.common.dto.AdminCouponSaveRequest;
import com.mall.trade.common.dto.AdminCouponVO;
import com.mall.trade.common.dto.CouponRecordVO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台优惠券模板管理 /api/admin/coupon(见《接口文档.md》3.10，需管理员登录)。
 *
 * <p><b>P5 步骤 C：本控制器已改为"薄转发"</b>——路径、方法、参数、响应 JSON 全部不变，
 * 实现改为调 {@link MarketingClient} 打 {@code mall-marketing} 的
 * {@code /internal/v1/marketing/admin/coupon/**}。
 *
 * <p><b>为什么后台不直接经网关路由到营销域</b>：管理端鉴权（{@code AdminAuthInterceptor}，
 * P8-2a 起**只信任网关注入的身份头**，不验签、不查账号表）还在单体。
 * 与 P2 内容域同一口径：**鉴权留在单体、业务落营销域**。因此网关谓词只有 {@code /api/coupon,/api/coupon/**}，
 * 刻意**不**包含 {@code /api/admin/coupon/**}——否则后台券管理会在没有管理端鉴权的情况下对外裸奔。
 * 活体脚本里有一条断言专门守它：不带身份头（直连端口）访问本路径必须 401「未登录」。
 *
 * <p><b>错误码与文案必须原样透传</b>：券的 400/409（{@code 该券已有人领取，无法删除(可停用)}、
 * {@code 减免金额不能大于门槛金额}、{@code 暂仅支持满减券}、{@code 固定时间段类型需填写开始时间}、
 * {@code 时间格式错误，示例 yyyy-MM-ddTHH:mm:ss}、{@code 券模板不存在}）由营销域抛出，
 * {@code MarketingClient} 包成 {@code BusinessException(code, message)}，
 * 本控制器**不做任何转换**（不 try/catch、不包装成 500）——包装会让后台页面从
 * "这条券删不了"变成"系统繁忙"，那是 C1 基线的破坏。
 *
 * <h2>为什么它住在 {@code admin.controller} 而不是原来的 {@code sms.controller}</h2>
 * P5 步骤 C 之后单体已经没有 {@code sms} 域（券的 domain/mapper/service/dto 全部删除或上移契约），
 * 这里剩下的只是"**管理端面**的一个转发入口"——与 {@code AdminMemberController} 等同族。
 * 留一个只剩转发器的 {@code sms} 包会让人以为"单体还有营销域代码"。
 */
@Tag(name = "后台-优惠券管理")
@RestController
@RequestMapping("/api/admin/coupon")
@RequiredArgsConstructor
public class AdminCouponController {

    private final MarketingClient marketingClient;

    @GetMapping("/page")
    public ApiResponse<PageResult<AdminCouponVO>> page(
            @Parameter(description = "券名关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态 0启用 1停用") @RequestParam(required = false) Integer status,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(marketingClient.adminPage(keyword, status, page.getPageNum(), page.getPageSize()));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody AdminCouponSaveRequest request) {
        return ApiResponse.ok(marketingClient.adminCreate(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@Parameter(description = "券模板ID", example = "3") @PathVariable Long id,
                                    @RequestBody AdminCouponSaveRequest request) {
        marketingClient.adminUpdate(id, request);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/disable")
    public ApiResponse<Void> disable(@Parameter(description = "券模板ID", example = "3") @PathVariable Long id) {
        marketingClient.adminDisable(id);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/enable")
    public ApiResponse<Void> enable(@Parameter(description = "券模板ID(恢复发券)", example = "1") @PathVariable Long id) {
        marketingClient.adminEnable(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@Parameter(description = "券模板ID(仅未发放可删)", example = "3")
                                    @PathVariable Long id) {
        marketingClient.adminDelete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/records")
    public ApiResponse<PageResult<CouponRecordVO>> records(@Parameter(description = "券模板ID", example = "3")
                                                           @PathVariable Long id,
                                                           @ParameterObject PageQuery page) {
        return ApiResponse.ok(marketingClient.adminRecords(id, page.getPageNum(), page.getPageSize()));
    }
}
