package com.mall.usercenter.service;

import com.mall.usercenter.dto.CartItemVO;

import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 购物车服务(登录态，见《接口文档.md》2.4)。
 */
public interface CartService {

    List<CartItemVO> list(Long memberId);

    /** 加入购物车：同 SKU 数量累加，超库存上限返回 409 */
    void add(Long memberId, Long skuId, Integer quantity);

    void updateQuantity(Long memberId, Long itemId, Integer quantity);

    void updateChecked(Long memberId, Long itemId, boolean checked);

    void updateAllChecked(Long memberId, boolean checked);

    void removeItem(Long memberId, Long itemId);

    void removeChecked(Long memberId);

    /** 购物车角标数量(条目数或总件数，取总件数) */
    int count(Long memberId);
}
