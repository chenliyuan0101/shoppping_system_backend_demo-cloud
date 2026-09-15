package com.mall.trade.cms.controller;

import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.PageQuery;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.client.ContentInternalClient;
import com.mall.trade.common.dto.AdminNoticeVO;
import com.mall.trade.common.dto.NoticeSaveRequest;
import com.mall.trade.common.dto.StatusRequest;
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
 * 后台公告管理 /api/admin/notice（需管理员登录）。
 * P2 起与 {@link AdminBannerController} 一样是薄转发，说明见那边。
 */
@Tag(name = "后台-公告管理")
@RestController
@RequestMapping("/api/admin/notice")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final ContentInternalClient contentClient;

    @GetMapping("/page")
    public ApiResponse<PageResult<AdminNoticeVO>> page(
            @Parameter(description = "标题关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态 0停用 1启用") @RequestParam(required = false) Integer status,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(contentClient.noticePage(keyword, status, page.getPageNum(), page.getPageSize()));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody NoticeSaveRequest request) {
        return ApiResponse.ok(contentClient.createNotice(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@Parameter(description = "公告ID", example = "1") @PathVariable Long id,
                                    @RequestBody NoticeSaveRequest request) {
        contentClient.updateNotice(id, request);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@Parameter(description = "公告ID", example = "1") @PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        contentClient.updateNoticeStatus(id, request.getStatus());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@Parameter(description = "公告ID", example = "1") @PathVariable Long id) {
        contentClient.deleteNotice(id);
        return ApiResponse.ok();
    }
}
