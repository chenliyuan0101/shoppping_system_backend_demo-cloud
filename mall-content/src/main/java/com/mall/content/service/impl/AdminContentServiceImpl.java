package com.mall.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.content.domain.Banner;
import com.mall.content.domain.Notice;
import com.mall.content.dto.BannerSaveRequest;
import com.mall.content.dto.NoticeSaveRequest;
import com.mall.content.mapper.BannerMapper;
import com.mall.content.mapper.NoticeMapper;
import com.mall.content.service.AdminContentService;
import com.mall.content.support.BusinessException;
import com.mall.content.support.CacheKeys;
import com.mall.content.support.CacheService;
import com.mall.content.support.constant.EnableStatus;
import com.mall.content.support.PageKit;
import com.mall.content.support.PageResult;
import com.mall.content.support.RequestValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台内容管理实现。
 */
@Service
@RequiredArgsConstructor
public class AdminContentServiceImpl implements AdminContentService {

    private final BannerMapper bannerMapper;
    private final NoticeMapper noticeMapper;
    /** 内容改动后删除首页聚合缓存，前台立即看到最新轮播/公告 */
    private final CacheService cacheService;
    /** 请求参数校验(约束注解写在 DTO 字段上，此处统一触发) */
    private final RequestValidator requestValidator;

    // ==================== 轮播 ====================

    @Override
    @Transactional(readOnly = true)
    public PageResult<Banner> bannerPage(String keyword, Integer status, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<Banner> wrapper = new LambdaQueryWrapper<Banner>()
                .like(StringUtils.hasText(keyword), Banner::getTitle, keyword)
                .eq(status != null, Banner::getStatus, status)
                .orderByAsc(Banner::getSort).orderByAsc(Banner::getId);
        long total = bannerMapper.selectCount(wrapper);
        List<Banner> list = bannerMapper.selectList(wrapper.last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, list);
    }

    @Override
    public Long createBanner(BannerSaveRequest request) {
        validateBanner(request);
        Banner banner = new Banner();
        fillBanner(banner, request);
        banner.setStatus(request.getStatus() == null ? EnableStatus.ENABLED : request.getStatus());
        bannerMapper.insert(banner);
        cacheService.delete(CacheKeys.homeIndex());
        return banner.getId();
    }

    @Override
    public void updateBanner(Long id, BannerSaveRequest request) {
        Banner banner = requireBanner(id);
        if (StringUtils.hasText(request.getTitle())) {
            banner.setTitle(request.getTitle().trim());
        }
        if (StringUtils.hasText(request.getImageUrl())) {
            banner.setImageUrl(request.getImageUrl());
        }
        if (request.getLinkUrl() != null) {
            banner.setLinkUrl(request.getLinkUrl());
        }
        if (request.getSort() != null) {
            banner.setSort(request.getSort());
        }
        bannerMapper.updateById(banner);
        cacheService.delete(CacheKeys.homeIndex());
    }

    @Override
    public void updateBannerStatus(Long id, Integer status) {
        requireBanner(id);
        checkStatus(status);
        Banner update = new Banner();
        update.setId(id);
        update.setStatus(status);
        bannerMapper.updateById(update);
        cacheService.delete(CacheKeys.homeIndex());
    }

    @Override
    public void deleteBanner(Long id) {
        requireBanner(id);
        bannerMapper.deleteById(id);
        cacheService.delete(CacheKeys.homeIndex());
    }

    // ==================== 公告 ====================

    @Override
    @Transactional(readOnly = true)
    public PageResult<Notice> noticePage(String keyword, Integer status, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<Notice>()
                .like(StringUtils.hasText(keyword), Notice::getTitle, keyword)
                .eq(status != null, Notice::getStatus, status)
                .orderByAsc(Notice::getSort).orderByAsc(Notice::getId);
        long total = noticeMapper.selectCount(wrapper);
        List<Notice> list = noticeMapper.selectList(wrapper.last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, list);
    }

    @Override
    public Long createNotice(NoticeSaveRequest request) {
        requestValidator.check(request);
        Notice notice = new Notice();
        notice.setTitle(request.getTitle().trim());
        notice.setContent(request.getContent());
        notice.setSort(request.getSort() == null ? 0 : request.getSort());
        notice.setStatus(request.getStatus() == null ? EnableStatus.ENABLED : request.getStatus());
        notice.setPublishTime(LocalDateTime.now());
        noticeMapper.insert(notice);
        cacheService.delete(CacheKeys.homeIndex());
        return notice.getId();
    }

    @Override
    public void updateNotice(Long id, NoticeSaveRequest request) {
        Notice notice = requireNotice(id);
        if (StringUtils.hasText(request.getTitle())) {
            notice.setTitle(request.getTitle().trim());
        }
        if (request.getContent() != null) {
            notice.setContent(request.getContent());
        }
        if (request.getSort() != null) {
            notice.setSort(request.getSort());
        }
        noticeMapper.updateById(notice);
        cacheService.delete(CacheKeys.homeIndex());
    }

    @Override
    public void updateNoticeStatus(Long id, Integer status) {
        requireNotice(id);
        checkStatus(status);
        Notice update = new Notice();
        update.setId(id);
        update.setStatus(status);
        noticeMapper.updateById(update);
        cacheService.delete(CacheKeys.homeIndex());
    }

    @Override
    public void deleteNotice(Long id) {
        requireNotice(id);
        noticeMapper.deleteById(id);
        cacheService.delete(CacheKeys.homeIndex());
    }

    // ---------- private ----------

    private void validateBanner(BannerSaveRequest request) {
        requestValidator.check(request);
    }

    private void fillBanner(Banner banner, BannerSaveRequest request) {
        banner.setTitle(request.getTitle().trim());
        banner.setImageUrl(request.getImageUrl());
        banner.setLinkUrl(request.getLinkUrl());
        banner.setSort(request.getSort() == null ? 0 : request.getSort());
    }

    private Banner requireBanner(Long id) {
        Banner banner = bannerMapper.selectById(id);
        if (banner == null) {
            throw new BusinessException(404, "轮播不存在");
        }
        return banner;
    }

    private Notice requireNotice(Long id) {
        Notice notice = noticeMapper.selectById(id);
        if (notice == null) {
            throw new BusinessException(404, "公告不存在");
        }
        return notice;
    }

    private void checkStatus(Integer status) {
        if (status == null || (status != EnableStatus.DISABLED && status != EnableStatus.ENABLED)) {
            throw new BusinessException(400, "状态值仅支持 0/1");
        }
    }
}
