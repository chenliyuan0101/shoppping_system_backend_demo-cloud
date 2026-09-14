package com.mall.demo.oms.controller;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.AuthAttribute;
import com.mall.demo.common.PageQuery;
import com.mall.demo.common.PageResult;
import com.mall.demo.oms.dto.RefundVO;
import com.mall.demo.oms.dto.RejectRequest;
import com.mall.demo.oms.service.RefundService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台退款售后 /api/admin/refund(见《接口文档.md》3.6，需管理员登录)。
 */
@Tag(name = "后台-退款售后")
@RestController
@RequestMapping("/api/admin/refund")
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundService refundService;

    @GetMapping("/page")
    public ApiResponse<PageResult<RefundVO>> page(
            @Parameter(description = "状态 0待处理 1处理中 2已完成 3已拒绝 4已取消") @RequestParam(required = false) Integer status,
            @Parameter(description = "类型 1仅退款 2退货退款") @RequestParam(required = false) Integer refundType,
            @Parameter(description = "售后单号") @RequestParam(required = false) String refundNo,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(refundService.pageAdmin(status, refundType, refundNo, page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/{id}")
    public ApiResponse<RefundVO> detail(@Parameter(description = "售后单ID", example = "1") @PathVariable Long id) {
        return ApiResponse.ok(refundService.detailAdmin(id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Void> approve(@Parameter(description = "售后单ID", example = "1") @PathVariable Long id,
                                     @RequestAttribute(AuthAttribute.ADMIN_USER_ID) Long operatorId) {
        refundService.approve(id, operatorId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Void> reject(@Parameter(description = "售后单ID", example = "1") @PathVariable Long id,
                                    @RequestBody RejectRequest request,
                                    @RequestAttribute(AuthAttribute.ADMIN_USER_ID) Long operatorId) {
        refundService.reject(id, request, operatorId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/received")
    public ApiResponse<Void> received(@Parameter(description = "售后单ID(退货退款，确认收到退货)", example = "1")
                                      @PathVariable Long id,
                                      @RequestAttribute(AuthAttribute.ADMIN_USER_ID) Long operatorId) {
        refundService.received(id, operatorId);
        return ApiResponse.ok();
    }
}
