package com.mall.usercenter.controller;

import com.mall.usercenter.support.ApiResponse;
import com.mall.usercenter.support.MemberId;
import com.mall.usercenter.support.PageQuery;
import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.dto.FavoriteToggleVO;
import com.mall.usercenter.dto.FavoriteVO;
import com.mall.usercenter.dto.FootprintRecordRequest;
import com.mall.usercenter.dto.FootprintVO;
import com.mall.usercenter.service.FavoriteService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏与浏览足迹(登录态，见《接口文档.md》2.9)。
 */
@Tag(name = "用户-收藏足迹")
@RestController
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @PostMapping("/api/favorite/toggle")
    public ApiResponse<FavoriteToggleVO> toggle(@MemberId Long memberId,
                                                @RequestBody FootprintRecordRequest request) {
        boolean favorited = favoriteService.toggleFavorite(memberId, request.getSpuId());
        FavoriteToggleVO vo = new FavoriteToggleVO();
        vo.setFavorited(favorited);
        return ApiResponse.ok(vo);
    }

    @GetMapping("/api/favorite/page")
    public ApiResponse<PageResult<FavoriteVO>> favoritePage(@MemberId Long memberId,
                                                            @ParameterObject PageQuery page) {
        return ApiResponse.ok(favoriteService.favoritePage(memberId, page.getPageNum(), page.getPageSize()));
    }

    @PostMapping("/api/footprint/record")
    public ApiResponse<Void> record(@MemberId Long memberId, @RequestBody FootprintRecordRequest request) {
        favoriteService.recordFootprint(memberId, request.getSpuId());
        return ApiResponse.ok();
    }

    @GetMapping("/api/footprint/page")
    public ApiResponse<PageResult<FootprintVO>> footprintPage(@MemberId Long memberId,
                                                              @ParameterObject PageQuery page) {
        return ApiResponse.ok(favoriteService.footprintPage(memberId, page.getPageNum(), page.getPageSize()));
    }

    @DeleteMapping("/api/footprint")
    public ApiResponse<Void> clear(@MemberId Long memberId) {
        favoriteService.clearFootprint(memberId);
        return ApiResponse.ok();
    }
}
