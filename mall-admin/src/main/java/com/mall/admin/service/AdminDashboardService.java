package com.mall.admin.service;

import com.mall.admin.dto.DashboardVO;

import java.util.List;

/**
 * 后台数据看板（见《接口文档.md》3.2，需管理员登录）。
 *
 * <p>实现契约（P7 §3）：
 * <ul>
 *   <li><b>并行聚合</b>：trade / product / user 三个域的取数必须并发发起，总耗时接近最慢的那一个；</li>
 *   <li><b>短 TTL 缓存 + 主动失效</b>：分钟级 TTL（{@code DashboardCache}），改数据后换"代"；</li>
 *   <li><b>降级</b>：任一依赖不可用时仍 {@code HTTP 200 + code=0 + 键路径集合不变}，
 *       缺的那部分回 0 / 空数组（不是 null、不是 500），并留一条 {@code log.warn}。</li>
 * </ul>
 */
public interface AdminDashboardService {

    DashboardVO.Summary summary();

    List<DashboardVO.TrendItem> trend(int days);

    List<DashboardVO.TopItem> top(String type, int limit);
}
