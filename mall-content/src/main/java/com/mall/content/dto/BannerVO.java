package com.mall.content.dto;

import lombok.Data;

/**
 * 轮播 VO(前台展示)。
 */
@Data
public class BannerVO {

    private Long id;
    private String title;
    private String imageUrl;
    private String linkUrl;
}
