package com.mall.content.internal;

import com.mall.content.domain.Notice;
import com.mall.content.dto.NoticeSaveRequest;
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
 * 公告的内部接口（与 {@link InternalBannerController} 对称，说明见那边）。
 */
@RestController
@RequestMapping("/internal/v1/content/notice")
@RequiredArgsConstructor
public class InternalNoticeController {

    private final AdminContentService adminContentService;

    @GetMapping("/page")
    public ApiResponse<PageResult<Notice>> page(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize) {
        return ApiResponse.ok(adminContentService.noticePage(keyword, status, pageNum, pageSize));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody NoticeSaveRequest request) {
        return ApiResponse.ok(adminContentService.createNotice(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody NoticeSaveRequest request) {
        adminContentService.updateNotice(id, request);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        adminContentService.updateNoticeStatus(id, request.getStatus());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        adminContentService.deleteNotice(id);
        return ApiResponse.ok();
    }
}
