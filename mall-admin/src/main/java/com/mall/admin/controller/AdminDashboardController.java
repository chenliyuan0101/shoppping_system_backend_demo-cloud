package com.mall.admin.controller;

import com.mall.admin.dto.DashboardVO;
import com.mall.admin.service.AdminDashboardService;
import com.mall.admin.support.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台数据看板 {@code /api/admin/dashboard}（见《接口文档.md》3.2，需管理员登录）。
 *
 * <h2>C1：与单体 {@code com.mall.demo.admin.controller.AdminDashboardController} 逐字相同</h2>
 * <pre>
 *   GET /api/admin/dashboard/summary                     → ApiResponse&lt;DashboardVO.Summary&gt;
 *   GET /api/admin/dashboard/trend?days=7                → ApiResponse&lt;List&lt;DashboardVO.TrendItem&gt;&gt;
 *   GET /api/admin/dashboard/top?type=sales&amp;limit=10     → ApiResponse&lt;List&lt;DashboardVO.TopItem&gt;&gt;
 * </pre>
 * 三个映射的路径、HTTP 方法、参数名、默认值（{@code days=7} / {@code type=sales} / {@code limit=10}）、
 * 以及返回的字段名全部照抄——**连默认值都必须是同几个数**：默认值不同 ⇒ 前端不传参时拿到的图不同。
 *
 * <p>{@code days}/{@code limit} 的钳制（≤30 / ≤20）**不在 BFF**：它属于"统计口径"，
 * 由属主域（交易域 {@code PageKit.size}、商品域同样的钳制）执行，BFF 原样透传参数。
 * 这样"钳制规则"只有一个属主，C1 也自然一致（单体当年就是转发给同一个域实现的）。
 *
 * <p>本控制器的方法体只有一行委托：组装、并行、缓存、降级都在
 * {@link com.mall.admin.service.impl.AdminDashboardServiceImpl}（P7 §3 的核心类）。
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
