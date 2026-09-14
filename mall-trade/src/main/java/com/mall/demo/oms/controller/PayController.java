package com.mall.demo.oms.controller;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.MemberId;
import com.mall.demo.oms.dto.PayMockRequest;
import com.mall.demo.oms.dto.PayResultVO;
import com.mall.demo.oms.service.OrderService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟支付 /api/pay(见《接口文档.md》2.7，登录态仅本人订单)。
 */
@Tag(name = "用户-模拟支付")
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PayController {

    private final OrderService orderService;

    @PostMapping("/mock")
    public ApiResponse<Void> mock(@MemberId Long memberId, @RequestBody PayMockRequest request) {
        orderService.payMock(memberId, request);
        return ApiResponse.ok();
    }

    @GetMapping("/result/{orderNo}")
    public ApiResponse<PayResultVO> result(@MemberId Long memberId,
                                           @Parameter(description = "订单号", example = "202609070000000001")
                                           @PathVariable String orderNo) {
        Integer payStatus = orderService.payResult(memberId, orderNo);
        PayResultVO vo = new PayResultVO();
        vo.setOrderNo(orderNo);
        vo.setPayStatus(payStatus);
        return ApiResponse.ok(vo);
    }
}
