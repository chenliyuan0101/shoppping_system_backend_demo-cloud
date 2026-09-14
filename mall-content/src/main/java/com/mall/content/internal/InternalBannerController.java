package com.mall.content.internal;

import com.mall.content.domain.Banner;
import com.mall.content.dto.BannerSaveRequest;
import com.mall.content.service.AdminContentService;
import com.mall.content.support.ApiResponse;
import com.mall.content.support.PageResult;
import com.mall.content.support.dto.StatusRequest;
import lombok.RequiredArgsConstructor;
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
 * 轮播的内部接口（服务间调用，{@code /internal/**} 由 {@code InternalApiAuthInterceptor} 守）。
 *
 * <p><b>P2 决策 1 的现状</b>：这几个端点当前**只有单体在调**——单体保留 {@code /api/admin/banner/**}
 * 的登录态与对外契约，鉴权通过后原样转发到这里（业务与数据已经搬进本服务，本服务是唯一写入方）。
 * 等 P3（网关验签 + 身份透传）或 P7（`mall-admin` BFF）落地，这层转发会被删除，
 * 届时本控制器直接升级成对外端点——**因此这里的入参/出参与对外契约是同一套形状**。
 *
 * <p>响应里直接返回实体 {@link Banner}：这是**故意的**，因为它就是
 * {@code /api/admin/banner/page} 现在的 JSON 形状（C1 要求逐字不变）；
 * 到 P8「mall-api 收身」时再和各服务一起换成显式 DTO + 契约测试。
 */
@RestController
@RequestMapping("/internal/v1/content/banner")
@RequiredArgsConstructor
public class InternalBannerController {

    private final AdminContentService adminContentService;

    @GetMapping("/page")
    public ApiResponse<PageResult<Banner>> page(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize) {
        return ApiResponse.ok(adminContentService.bannerPage(keyword, status, pageNum, pageSize));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody BannerSaveRequest request) {
        return ApiResponse.ok(adminContentService.createBanner(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody BannerSaveRequest request) {
        adminContentService.updateBanner(id, request);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        adminContentService.updateBannerStatus(id, request.getStatus());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        adminContentService.deleteBanner(id);
        return ApiResponse.ok();
    }
}
