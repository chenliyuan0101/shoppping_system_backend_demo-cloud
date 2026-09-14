package com.mall.demo.admin.controller;

import com.mall.demo.admin.dto.MemberAdminVO;
import com.mall.demo.admin.service.AdminMemberService;
import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.dto.StatusRequest;
import com.mall.demo.common.PageQuery;
import com.mall.demo.common.PageResult;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 后台会员管理 /api/admin/member(见《接口文档.md》3.7，需管理员登录)。
 */
@Tag(name = "后台-会员管理")
@RestController
@RequestMapping("/api/admin/member")
@RequiredArgsConstructor
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @GetMapping("/page")
    public ApiResponse<PageResult<MemberAdminVO>> page(
            @Parameter(description = "用户名/手机号/昵称", example = "demo") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态 0禁用 1正常", example = "1") @RequestParam(required = false) Integer status,
            @Parameter(description = "注册日期起 yyyy-MM-dd(含)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateStart,
            @Parameter(description = "注册日期止 yyyy-MM-dd(含)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateEnd,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(adminMemberService.page(keyword, status,
                createDateStart == null ? null : createDateStart.atStartOfDay(),
                createDateEnd == null ? null : createDateEnd.plusDays(1).atStartOfDay(),
                page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/{id}")
    public ApiResponse<MemberAdminVO> detail(@Parameter(description = "会员ID", example = "1") @PathVariable Long id) {
        return ApiResponse.ok(adminMemberService.detail(id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@Parameter(description = "会员ID", example = "1") @PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        adminMemberService.updateStatus(id, request.getStatus());
        return ApiResponse.ok();
    }
}
