package com.mall.demo.admin.controller;

import com.mall.demo.admin.dto.DashboardVO;
import com.mall.demo.admin.service.AdminDashboardService;
import com.mall.demo.common.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台数据看板 /api/admin/dashboard(见《接口文档.md》3.2，需管理员登录)。
 */
@Tag(name = "后台-数据看板")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @GetMapping("/summary")
    public ApiResponse<DashboardVO.Summary> summary() {
        return ApiResponse.ok(adminDashboardService.summary());
    }

    @GetMapping("/trend")
    public ApiResponse<List<DashboardVO.TrendItem>> trend(
            @Parameter(description = "近 N 天(≤30)", example = "7") @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(adminDashboardService.trend(days));
    }

    @GetMapping("/top")
    public ApiResponse<List<DashboardVO.TopItem>> top(
            @Parameter(description = "sales=销量 amount=销售额", example = "sales") @RequestParam(defaultValue = "sales") String type,
            @Parameter(description = "数量", example = "10") @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(adminDashboardService.top(type, limit));
    }
}
