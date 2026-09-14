package com.mall.demo.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端公告的<b>契约快照</b>（{@code /api/admin/notice/**} 的响应体形状）。
 * 字段与改造前直接序列化 {@code cms_notice} 实体逐字一致（C1）。说明见 {@link AdminBannerVO}。
 */
@Data
public class AdminNoticeVO {

    private Long id;
    private String title;
    private String content;
    private Integer sort;
    /** 0停用 1启用 */
    private Integer status;
    private LocalDateTime publishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
