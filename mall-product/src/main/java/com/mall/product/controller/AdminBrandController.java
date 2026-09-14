package com.mall.product.controller;

import com.mall.product.support.ApiResponse;
import com.mall.product.support.PageQuery;
import com.mall.product.support.PageResult;
import com.mall.product.domain.Brand;
import com.mall.product.dto.BrandSaveRequest;
import com.mall.product.service.AdminProductService;
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

import java.util.List;

/**
 * 后台品牌管理 /api/admin/brand(见《接口文档.md》3.3，需管理员登录)。
 * 商品新增/编辑可挂 brandId，列表可按品牌筛选。
 */
@Tag(name = "后台-品牌管理")
@RestController
@RequestMapping("/api/admin/brand")
@RequiredArgsConstructor
public class AdminBrandController {

    private final AdminProductService adminProductService;

    @GetMapping("/page")
    public ApiResponse<PageResult<Brand>> page(
            @Parameter(description = "品牌名关键字", example = "华") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态 0停用 1启用", example = "1") @RequestParam(required = false) Integer status,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(adminProductService.pageBrands(keyword, status, page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/list")
    public ApiResponse<List<Brand>> listEnabled() {
        return ApiResponse.ok(adminProductService.listEnabledBrands());
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody BrandSaveRequest request) {
        return ApiResponse.ok(adminProductService.createBrand(
                request.getName(), request.getLogo(), request.getSort(), request.getStatus()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@Parameter(description = "品牌ID", example = "1") @PathVariable Long id,
                                    @RequestBody BrandSaveRequest request) {
        adminProductService.updateBrand(id, request.getName(), request.getLogo(),
                request.getSort(), request.getStatus());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@Parameter(description = "品牌ID", example = "1") @PathVariable Long id) {
        adminProductService.deleteBrand(id);
        return ApiResponse.ok();
    }
}
