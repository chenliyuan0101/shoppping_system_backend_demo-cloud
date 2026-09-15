package com.mall.usercenter.service;

import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.dto.FavoriteVO;
import com.mall.usercenter.dto.FootprintVO;
import com.mall.common.support.MemberId;

/**
 * 收藏 + 浏览足迹服务(登录态，见《接口文档.md》2.9)。
 */
public interface FavoriteService {

    /** 收藏/取消收藏，返回当前是否已收藏 */
    boolean toggleFavorite(Long memberId, Long spuId);

    PageResult<FavoriteVO> favoritePage(Long memberId, long pageNum, long pageSize);

    void recordFootprint(Long memberId, Long spuId);

    PageResult<FootprintVO> footprintPage(Long memberId, long pageNum, long pageSize);

    void clearFootprint(Long memberId);
}
