package com.mall.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.content.client.ProductFeedClient;
import com.mall.content.client.ProductFeedUnavailableException;
import com.mall.content.domain.Banner;
import com.mall.content.domain.Notice;
import com.mall.content.dto.BannerVO;
import com.mall.content.dto.HomeData;
import com.mall.content.dto.NoticeVO;
import com.mall.content.mapper.BannerMapper;
import com.mall.content.mapper.NoticeMapper;
import com.mall.content.service.HomeService;
import com.mall.content.support.CacheKeys;
import com.mall.content.support.CacheService;
import com.mall.content.support.constant.EnableStatus;
import com.mall.content.support.dto.HomeFeedVO;
import com.mall.content.support.dto.ProductListItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 首页聚合：轮播/公告来自本域（{@code cms_*}），类目/热门/新品来自商品域。
 *
 * <p><b>P2 的关键变化</b>：商品区块从"本进程调用"变成**跨进程调用**（{@link ProductFeedClient}），
 * 因此这里按《微服务改造方案.md》§2.9 定义了降级：
 * <ul>
 *   <li>商品域不可用 → 只返回 Banner + 公告，三个商品区块为空数组，HTTP 200 且 {@code code=0}，**绝不 5xx**；</li>
 *   <li>降级结果只缓存 5 秒（正常 45 秒）：既不把故障期的每个请求都变成 300ms 超时，也不让首页
 *       在商品域恢复后长时间停在降级态；</li>
 *   <li>降级只写日志——C1 不允许新增 {@code partial} 之类字段，前端既有的空数据兜底逻辑不变（前端零改动）。</li>
 * </ul>
 *
 * <p>顺序上**先调远程、再查本库**，且 {@link #home()} 不加 {@code @Transactional}：
 * 远程调用（最长 300ms）不应该把数据库连接占在手里。本域两次读都是自动提交的只读查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeServiceImpl implements HomeService {

    private static final int SECTION_SIZE = 8;

    /** 首页聚合缓存：45 秒（内容改动由后台写操作直接删除该 key，前台立即生效） */
    private static final Duration HOME_TTL = Duration.ofSeconds(45);

    /** 降级结果缓存：5 秒（§2.9 第 3 条） */
    private static final Duration HOME_DEGRADED_TTL = Duration.ofSeconds(5);

    private final BannerMapper bannerMapper;
    private final NoticeMapper noticeMapper;
    private final ProductFeedClient productFeedClient;
    private final CacheService cacheService;

    @Override
    public HomeData home() {
        HomeData cached = cacheService.getJson(CacheKeys.homeIndex(), HomeData.class);
        if (cached != null) {
            return cached;
        }

        HomeFeedVO feed = null;
        Duration ttl = HOME_TTL;
        try {
            feed = productFeedClient.homeFeed(SECTION_SIZE);
        } catch (ProductFeedUnavailableException e) {
            // 降级：首页仍然可用，只是少了商品区块
            log.warn("首页商品区块降级(商品域不可用): {}", e.getMessage());
            ttl = HOME_DEGRADED_TTL;
        }

        HomeData data = HomeData.builder()
                .banners(banners())
                .notices(notices())
                .categories(feed == null ? List.of() : emptyIfNull(feed.getCategories()))
                .hotProducts(feed == null ? List.of() : emptyIfNull(feed.getHotProducts()))
                .newProducts(feed == null ? List.of() : emptyIfNull(feed.getNewProducts()))
                .build();
        cacheService.setJson(CacheKeys.homeIndex(), data, ttl);
        return data;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BannerVO> banners() {
        List<Banner> list = bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getStatus, EnableStatus.ENABLED)
                .orderByAsc(Banner::getSort).orderByAsc(Banner::getId)
                .last("LIMIT 0," + SECTION_SIZE));
        return list.stream().map(b -> {
            BannerVO vo = new BannerVO();
            vo.setId(b.getId());
            vo.setTitle(b.getTitle());
            vo.setImageUrl(b.getImageUrl());
            vo.setLinkUrl(b.getLinkUrl());
            return vo;
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NoticeVO> notices() {
        List<Notice> list = noticeMapper.selectList(new LambdaQueryWrapper<Notice>()
                .eq(Notice::getStatus, EnableStatus.ENABLED)
                .orderByAsc(Notice::getSort).orderByAsc(Notice::getId)
                .last("LIMIT 0," + SECTION_SIZE));
        return list.stream().map(n -> {
            NoticeVO vo = new NoticeVO();
            vo.setId(n.getId());
            vo.setTitle(n.getTitle());
            vo.setCreateTime(n.getCreateTime());
            return vo;
        }).toList();
    }

    private static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
