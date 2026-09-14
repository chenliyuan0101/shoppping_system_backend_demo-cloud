package com.mall.demo.admin.controller;

import com.mall.demo.admin.dto.StatRefreshVO;
import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.MallTime;
import com.mall.demo.common.dto.OrderDailyStatVO;
import com.mall.demo.common.dto.StatOverviewVO;
import com.mall.demo.oms.service.StatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 订单统计（后台）。
 *
 * <p>统计表 `oms_order_daily_stat` 是**派生数据**：日常由领域事件触发重算，
 * 这里的 refresh 接口用于"人工追平"（例如直改库、补历史、事件丢失之后）。
 * 因为重算是"从源表按日重算"，所以可以放心重复执行。
 */
@Tag(name = "后台-订单统计")
@RestController
@RequestMapping("/api/admin/stat")
@RequiredArgsConstructor
public class AdminStatController {

    private final StatService statService;

    @Operation(summary = "订单概览(今天 + 近 7 天合计)")
    @GetMapping("/overview")
    public ApiResponse<StatOverviewVO> overview() {
        return ApiResponse.ok(statService.overview());
    }

    @Operation(summary = "按日统计列表(近 N 天，默认 7)")
    @GetMapping("/daily")
    public ApiResponse<List<OrderDailyStatVO>> daily(
            @Parameter(description = "天数(1~90)", example = "7")
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(statService.recentDays(days));
    }

    @Operation(summary = "重算某天统计(默认今天；幂等，可随时追平)")
    @PostMapping("/refresh")
    public ApiResponse<StatRefreshVO> refresh(
            @Parameter(description = "日期 yyyy-MM-dd；留空=今天", example = "2026-09-11")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date == null ? MallTime.today() : date;
        boolean ok = statService.refreshDay(target);
        StatRefreshVO data = new StatRefreshVO();
        data.setDate(target.toString());
        data.setRefreshed(ok);
        return ApiResponse.ok(data);
    }
}
