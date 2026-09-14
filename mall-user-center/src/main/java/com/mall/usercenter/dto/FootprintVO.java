package com.mall.usercenter.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 浏览足迹列表项。
 */
@Data
public class FootprintVO {

    private Long spuId;
    private String title;
    private String mainImage;
    private Long price;
    private LocalDateTime lastViewTime;
}
