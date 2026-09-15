package com.mall.usercenter.service;

import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.dto.NotificationVO;
import com.mall.common.support.MemberId;

/**
 * 站内消息（由 MQ 领域事件驱动生成，见《后端RabbitMQ使用手册.md》）。
 */
public interface NotificationService {

    /**
     * 幂等写入一条站内消息（命中 `(member_id,type,biz_no)` 唯一键时跳过）。
     *
     * @return true=本次真的插入了（false=已存在，重复事件）
     */
    boolean push(Long memberId, String type, String title, String content, String bizNo);

    /** 我的消息分页（按时间倒序） */
    PageResult<NotificationVO> page(Long memberId, long pageNum, long pageSize);

    /** 未读条数（前台角标） */
    long unreadCount(Long memberId);

    /** 标记单条已读（只能标记自己的；不存在或不属于自己返回 false） */
    boolean markRead(Long memberId, long id);

    /** 全部标记已读，返回影响条数 */
    int markAllRead(Long memberId);
}
