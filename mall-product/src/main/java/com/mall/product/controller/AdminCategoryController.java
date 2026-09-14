package com.mall.product.controller;

import com.mall.product.support.ApiResponse;
import com.mall.product.support.dto.CategoryNode;
import com.mall.product.support.dto.StatusRequest;
import com.mall.product.dto.CategorySaveRequest;
import com.mall.product.service.AdminProductService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台类目管理 /api/admin/category(见《接口文档.md》3.3，全量接口需管理员登录)。
 */
@Tag(name = "后台-类目管理")
@RestController
@RequestMapping("/api/admin/category")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final AdminProductService adminProductService;

    @GetMapping("/tree")
    public ApiResponse<List<CategoryNode>> tree() {
        return ApiResponse.ok(adminProductService.categoryTree());
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody CategorySaveRequest request) {
        return ApiResponse.ok(adminProductService.createCategory(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@Parameter(description = "类目ID", example = "11") @PathVariable Long id,
                                    @RequestBody CategorySaveRequest request) {
        adminProductService.updateCategory(id, request);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@Parameter(description = "类目ID", example = "11") @PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        adminProductService.updateCategoryStatus(id, request.getStatus());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@Parameter(description = "类目ID", example = "11") @PathVariable Long id) {
        adminProductService.deleteCategory(id);
        return ApiResponse.ok();
    }
}
