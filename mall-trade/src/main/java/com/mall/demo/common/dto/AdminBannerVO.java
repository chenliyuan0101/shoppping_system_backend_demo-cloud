package com.mall.demo.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端轮播的<b>契约快照</b>（{@code /api/admin/banner/**} 的响应体形状）。
 *
 * <p>它等于改造前直接序列化 {@code cms_banner} 实体得到的 JSON：字段名/类型逐字相同
 * （C1：对外契约不变，管理端前端零改动）。做成快照而不是继续用实体的原因：
 * 拆分后实体已经搬到 {@code mall-content}，单体只剩"转发 + 鉴权"，
 * 再直连实体会重新引入跨域表访问——这正是 P0 闸门要拦的形态。
 */
@Data
public class AdminBannerVO {

    private Long id;
    private String title;
    private String imageUrl;
    private String linkUrl;
    private Integer sort;
    /** 0停用 1启用 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
