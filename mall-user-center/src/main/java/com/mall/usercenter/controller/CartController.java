package com.mall.usercenter.controller;

import com.mall.usercenter.support.ApiResponse;
import com.mall.common.support.MemberId;
import com.mall.usercenter.dto.CartAddRequest;
import com.mall.usercenter.dto.CartItemVO;
import com.mall.usercenter.dto.CartQuantityRequest;
import com.mall.usercenter.dto.CheckRequest;
import com.mall.usercenter.service.CartService;
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
 * 购物车 /api/cart(见《接口文档.md》2.4，全部需会员登录)。
 */
@Tag(name = "用户-购物车")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping("/list")
    public ApiResponse<List<CartItemVO>> list(@MemberId Long memberId) {
        return ApiResponse.ok(cartService.list(memberId));
    }

    @PostMapping("/add")
    public ApiResponse<Void> add(@MemberId Long memberId, @RequestBody CartAddRequest request) {
        cartService.add(memberId, request.getSkuId(), request.getQuantity());
        return ApiResponse.ok();
    }

    @PutMapping("/item/{itemId}")
    public ApiResponse<Void> updateQuantity(@MemberId Long memberId,
                                            @Parameter(description = "购物车条目ID", example = "1")
                                            @PathVariable Long itemId,
                                            @RequestBody CartQuantityRequest request) {
        cartService.updateQuantity(memberId, itemId, request.getQuantity());
        return ApiResponse.ok();
    }

    @PutMapping("/item/{itemId}/checked")
    public ApiResponse<Void> updateChecked(@MemberId Long memberId,
                                           @Parameter(description = "购物车条目ID", example = "1")
                                           @PathVariable Long itemId,
                                           @RequestBody CheckRequest request) {
        cartService.updateChecked(memberId, itemId, Boolean.TRUE.equals(request.getChecked()));
        return ApiResponse.ok();
    }

    @PutMapping("/checked-all")
    public ApiResponse<Void> updateAllChecked(@MemberId Long memberId, @RequestBody CheckRequest request) {
        cartService.updateAllChecked(memberId, Boolean.TRUE.equals(request.getChecked()));
        return ApiResponse.ok();
    }

    @DeleteMapping("/item/{itemId}")
    public ApiResponse<Void> removeItem(@MemberId Long memberId,
                                        @Parameter(description = "购物车条目ID", example = "1")
                                        @PathVariable Long itemId) {
        cartService.removeItem(memberId, itemId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/checked")
    public ApiResponse<Void> removeChecked(@MemberId Long memberId) {
        cartService.removeChecked(memberId);
        return ApiResponse.ok();
    }

    @GetMapping("/count")
    public ApiResponse<Integer> count(@MemberId Long memberId) {
        return ApiResponse.ok(cartService.count(memberId));
    }
}
