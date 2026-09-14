package com.mall.content.service;

import com.mall.content.dto.BannerVO;
import com.mall.content.dto.HomeData;
import com.mall.content.dto.NoticeVO;

import java.util.List;

/**
 * 首页内容服务(公开，见《接口文档.md》2.2)。
 */
public interface HomeService {

    HomeData home();

    List<BannerVO> banners();

    List<NoticeVO> notices();
}
