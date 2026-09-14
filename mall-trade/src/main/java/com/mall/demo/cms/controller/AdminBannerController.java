package com.mall.demo.cms.controller;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.PageQuery;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.client.ContentInternalClient;
import com.mall.demo.common.dto.AdminBannerVO;
import com.mall.demo.common.dto.BannerSaveRequest;
import com.mall.demo.common.dto.StatusRequest;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台轮播管理 /api/admin/banner（需管理员登录）。
 *
 * <p><b>P2 起这是"薄转发"</b>：鉴权（{@code AdminAuthInterceptor} 拦 {@code /api/admin/**}）与对外契约留在单体，
 * 业务逻辑与数据在 {@code mall-content}（唯一写入方）。转发理由与退场条件见
 * {@link ContentInternalClient} 的类注释与方案 §5 P2 决策 1。
 *
 * <p>注意它对客户端**完全不可见差别**：路径、入参、响应字段、错误码与文案都与改造前逐字一致
 * （响应形状由 {@link AdminBannerVO} 守住，错误码/文案由客户端原样透传）。
 */
@Tag(name = "后台-轮播管理")
@RestController
@RequestMapping("/api/admin/banner")
@RequiredArgsConstructor
public class AdminBannerController {

    private final ContentInternalClient contentClient;

    @GetMapping("/page")
    public ApiResponse<PageResult<AdminBannerVO>> page(
            @Parameter(description = "标题关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态 0停用 1启用") @RequestParam(required = false) Integer status,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(contentClient.bannerPage(keyword, status, page.getPageNum(), page.getPageSize()));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody BannerSaveRequest request) {
        return ApiResponse.ok(contentClient.createBanner(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@Parameter(description = "轮播ID", example = "1") @PathVariable Long id,
                                    @RequestBody BannerSaveRequest request) {
        contentClient.updateBanner(id, request);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@Parameter(description = "轮播ID", example = "1") @PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        contentClient.updateBannerStatus(id, request.getStatus());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@Parameter(description = "轮播ID", example = "1") @PathVariable Long id) {
        contentClient.deleteBanner(id);
        return ApiResponse.ok();
    }
}
