package com.mall.content.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告 VO(前台展示)。
 */
@Data
public class NoticeVO {

    private Long id;
    private String title;
    private LocalDateTime createTime;
}
