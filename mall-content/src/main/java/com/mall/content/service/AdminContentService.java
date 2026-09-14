package com.mall.content.service;

import com.mall.content.domain.Banner;
import com.mall.content.domain.Notice;
import com.mall.content.dto.BannerSaveRequest;
import com.mall.content.dto.NoticeSaveRequest;
import com.mall.content.support.PageResult;

/**
 * 后台内容管理：轮播 + 公告(后台管 → 前台 /api/banner/list、/api/notice/list、首页展示)。
 */
public interface AdminContentService {

    PageResult<Banner> bannerPage(String keyword, Integer status, long pageNum, long pageSize);

    Long createBanner(BannerSaveRequest request);

    void updateBanner(Long id, BannerSaveRequest request);

    void updateBannerStatus(Long id, Integer status);

    void deleteBanner(Long id);

    PageResult<Notice> noticePage(String keyword, Integer status, long pageNum, long pageSize);

    Long createNotice(NoticeSaveRequest request);

    void updateNotice(Long id, NoticeSaveRequest request);

    void updateNoticeStatus(Long id, Integer status);

    void deleteNotice(Long id);
}
