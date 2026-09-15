package com.mall.trade.oms.controller;

import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.IdempotencyGuard;
import com.mall.common.support.MemberId;
import com.mall.trade.common.PageQuery;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.RateLimit;
import com.mall.trade.oms.dto.OrderCreateRequest;
import com.mall.trade.oms.dto.OrderDetailVO;
import com.mall.trade.oms.dto.OrderListVO;
import com.mall.trade.oms.service.OrderService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的订单 /api/order(见《接口文档.md》2.6，需会员登录，仅本人订单)。
 */
@Tag(name = "用户-订单")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    /** 下单幂等：前端带 Idempotency-Key 时，重复提交只落一单 */
    private final IdempotencyGuard idempotencyGuard;

    @PostMapping("/preview")
    public ApiResponse<?> preview(@MemberId Long memberId, @RequestBody OrderCreateRequest request) {
        return ApiResponse.ok(orderService.preview(memberId, request));
    }

    @PostMapping("/create")
    @RateLimit(scope = "order_create", limit = 10, windowSeconds = 60, by = RateLimit.By.USER)
    public ApiResponse<String> create(@MemberId Long memberId,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestBody OrderCreateRequest request) {
        return ApiResponse.ok(idempotencyGuard.execute(memberId, idempotencyKey,
                () -> orderService.createOrder(memberId, request)));
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<OrderListVO>> page(
            @MemberId Long memberId,
            @Parameter(description = "订单状态 0-7(空=全部)：6退款中 7已退款", example = "0")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "true=只看退款/售后(所有有售后单的订单)")
            @RequestParam(required = false) Boolean afterSale,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(orderService.page(memberId, status, afterSale, page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<OrderDetailVO> detail(@MemberId Long memberId,
                                             @Parameter(description = "订单号", example = "202609070000000001")
                                             @PathVariable String orderNo) {
        return ApiResponse.ok(orderService.detail(memberId, orderNo));
    }

    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Void> cancel(@MemberId Long memberId,
                                    @Parameter(description = "订单号", example = "202609070000000001")
                                    @PathVariable String orderNo) {
        orderService.cancel(memberId, orderNo);
        return ApiResponse.ok();
    }

    @PostMapping("/{orderNo}/confirm")
    public ApiResponse<Void> confirm(@MemberId Long memberId,
                                     @Parameter(description = "订单号", example = "202609070000000001")
                                     @PathVariable String orderNo) {
        orderService.confirm(memberId, orderNo);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{orderNo}")
    public ApiResponse<Void> delete(@MemberId Long memberId,
                                    @Parameter(description = "订单号(仅已完成/已取消可删)", example = "202609070000000001")
                                    @PathVariable String orderNo) {
        orderService.deleteOrder(memberId, orderNo);
        return ApiResponse.ok();
    }
}
