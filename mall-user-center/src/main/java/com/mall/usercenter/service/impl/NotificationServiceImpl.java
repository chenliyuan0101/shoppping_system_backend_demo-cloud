package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mall.common.constant.YesNo;
import com.mall.common.support.PageKit;
import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.domain.Notification;
import com.mall.usercenter.dto.NotificationVO;
import com.mall.usercenter.mapper.NotificationMapper;
import com.mall.usercenter.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 站内消息实现。
 *
 * <p>写入口只有一个（{@link #push}），由 MQ 事件消费者调用；读接口给前台用。
 * 幂等靠数据库唯一键 `(member_id,type,biz_no)`，见 {@link NotificationMapper#insertIgnore}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;

    @Override
    public boolean push(Long memberId, String type, String title, String content, String bizNo) {
        if (memberId == null || type == null || bizNo == null) {
            log.warn("站内消息参数不完整，忽略: memberId={} type={} bizNo={}", memberId, type, bizNo);
            return false;
        }
        int inserted = notificationMapper.insertIgnore(memberId, type, title, content, bizNo, LocalDateTime.now());
        if (inserted == 0) {
            log.debug("站内消息已存在(事件重复投递)，跳过: memberId={} type={} bizNo={}", memberId, type, bizNo);
        }
        return inserted > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<NotificationVO> page(Long memberId, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<Notification> base = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getMemberId, memberId)
                .orderByDesc(Notification::getId);
        long total = notificationMapper.selectCount(base);
        List<Notification> rows = notificationMapper.selectList(
                base.last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, rows.stream().map(this::toVo).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(Long memberId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getMemberId, memberId)
                .eq(Notification::getIsRead, YesNo.NO));
    }

    @Override
    public boolean markRead(Long memberId, long id) {
        int updated = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .set(Notification::getIsRead, YesNo.YES)
                .set(Notification::getReadTime, LocalDateTime.now())
                .eq(Notification::getId, id)
                .eq(Notification::getMemberId, memberId)
                .eq(Notification::getIsRead, YesNo.NO));
        return updated > 0;
    }

    @Override
    public int markAllRead(Long memberId) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .set(Notification::getIsRead, YesNo.YES)
                .set(Notification::getReadTime, LocalDateTime.now())
                .eq(Notification::getMemberId, memberId)
                .eq(Notification::getIsRead, YesNo.NO));
    }

    private NotificationVO toVo(Notification n) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setType(n.getType());
        vo.setTitle(n.getTitle());
        vo.setContent(n.getContent());
        vo.setBizNo(n.getBizNo());
        vo.setIsRead(n.getIsRead());
        vo.setCreateTime(n.getCreateTime());
        return vo;
    }
}
