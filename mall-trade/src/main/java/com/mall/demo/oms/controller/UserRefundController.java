package com.mall.demo.oms.controller;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.MemberId;
import com.mall.demo.common.PageQuery;
import com.mall.demo.common.PageResult;
import com.mall.demo.oms.dto.RefundApplyRequest;
import com.mall.demo.oms.dto.RefundVO;
import com.mall.demo.oms.dto.ReturnLogisticsRequest;
import com.mall.demo.oms.service.RefundService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端退款售后 /api/refund(登录，仅本人)。
 */
@Tag(name = "用户-退款售后")
@RestController
@RequestMapping("/api/refund")
@RequiredArgsConstructor
public class UserRefundController {

    private final RefundService refundService;

    @PostMapping("/apply")
    public ApiResponse<String> apply(@MemberId Long memberId, @RequestBody RefundApplyRequest request) {
        return ApiResponse.ok(refundService.apply(memberId, request));
    }

    @GetMapping("/page")
    public ApiResponse<PageResult<RefundVO>> page(@MemberId Long memberId,
                                                  @Parameter(description = "状态 0待处理 1处理中 2已完成 3已拒绝 4已取消")
                                                  @RequestParam(required = false) Integer status,
                                                  @ParameterObject PageQuery page) {
        return ApiResponse.ok(refundService.pageUser(memberId, status, page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/{refundNo}")
    public ApiResponse<RefundVO> detail(@MemberId Long memberId, @PathVariable String refundNo) {
        return ApiResponse.ok(refundService.detailUser(memberId, refundNo));
    }

    @PostMapping("/{refundNo}/cancel")
    public ApiResponse<Void> cancel(@MemberId Long memberId, @PathVariable String refundNo) {
        refundService.cancelUser(memberId, refundNo);
        return ApiResponse.ok();
    }

    @PostMapping("/{refundNo}/return-logistics")
    public ApiResponse<Void> submitReturnLogistics(@MemberId Long memberId,
                                                   @PathVariable String refundNo,
                                                   @RequestBody ReturnLogisticsRequest request) {
        refundService.submitReturnLogistics(memberId, refundNo, request);
        return ApiResponse.ok();
    }
}
