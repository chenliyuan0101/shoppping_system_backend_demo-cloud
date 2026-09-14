package com.mall.content.controller;

import com.mall.content.dto.BannerVO;
import com.mall.content.dto.HomeData;
import com.mall.content.dto.NoticeVO;
import com.mall.content.service.HomeService;
import com.mall.content.support.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台首页内容(公开，见《接口文档.md》2.2)。
 */
@Tag(name = "前台-首页内容")
@RestController
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @GetMapping("/api/home/index")
    public ApiResponse<HomeData> home() {
        return ApiResponse.ok(homeService.home());
    }

    @GetMapping("/api/banner/list")
    public ApiResponse<List<BannerVO>> banners() {
        return ApiResponse.ok(homeService.banners());
    }

    @GetMapping("/api/notice/list")
    public ApiResponse<List<NoticeVO>> notices() {
        return ApiResponse.ok(homeService.notices());
    }
}
