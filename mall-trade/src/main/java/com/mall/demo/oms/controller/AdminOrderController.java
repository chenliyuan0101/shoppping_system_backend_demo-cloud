package com.mall.demo.oms.controller;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.PageQuery;
import com.mall.demo.common.PageResult;
import com.mall.demo.oms.dto.AdminOrderDetailVO;
import com.mall.demo.oms.dto.AdminOrderListVO;
import com.mall.demo.oms.dto.CloseOrderRequest;
import com.mall.demo.oms.dto.ShipRequest;
import com.mall.demo.oms.service.AdminOrderQueryService;
import com.mall.demo.oms.service.OrderService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 后台订单管理 /api/admin/order(见《接口文档.md》3.5，全量接口需管理员登录)。
 */
@Tag(name = "后台-订单管理")
@RestController
@RequestMapping("/api/admin/order")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderQueryService adminOrderQueryService;
    private final OrderService orderService;

    @GetMapping("/page")
    public ApiResponse<PageResult<AdminOrderListVO>> page(
            @Parameter(description = "订单号(可模糊)", example = "20260907") @RequestParam(required = false) String orderNo,
            @Parameter(description = "会员用户名/手机号/昵称", example = "demo") @RequestParam(required = false) String memberKeyword,
            @Parameter(description = "订单状态 0-5", example = "1") @RequestParam(required = false) Integer status,
            @Parameter(description = "支付状态 0未支付 1已支付", example = "1") @RequestParam(required = false) Integer payStatus,
            @Parameter(description = "下单日期起 yyyy-MM-dd(含)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateStart,
            @Parameter(description = "下单日期止 yyyy-MM-dd(含)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateEnd,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(adminOrderQueryService.page(orderNo, memberKeyword, status, payStatus,
                createDateStart == null ? null : createDateStart.atStartOfDay(),
                createDateEnd == null ? null : createDateEnd.plusDays(1).atStartOfDay(),
                page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<AdminOrderDetailVO> detail(@Parameter(description = "订单号", example = "202609070000000001")
                                                  @PathVariable String orderNo) {
        return ApiResponse.ok(adminOrderQueryService.detail(orderNo));
    }

    @PostMapping("/{orderNo}/ship")
    public ApiResponse<Void> ship(@Parameter(description = "订单号", example = "202609070000000001")
                                  @PathVariable String orderNo,
                                  @RequestBody ShipRequest request) {
        orderService.shipByAdmin(orderNo, request);
        return ApiResponse.ok();
    }

    @PostMapping("/{orderNo}/close")
    public ApiResponse<Void> close(@Parameter(description = "订单号(仅待支付/待发货)", example = "202609070000000001")
                                   @PathVariable String orderNo,
                                   @RequestBody CloseOrderRequest request) {
        orderService.closeByAdmin(orderNo, request);
        return ApiResponse.ok();
    }
}
