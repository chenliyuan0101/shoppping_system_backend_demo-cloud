package com.mall.demo.admin.service;

import com.mall.demo.admin.dto.DashboardVO;

import java.util.List;

/**
 * 后台数据看板(见《接口文档.md》3.2)。
 */
public interface AdminDashboardService {

    DashboardVO.Summary summary();

    List<DashboardVO.TrendItem> trend(int days);

    List<DashboardVO.TopItem> top(String type, int limit);
}
