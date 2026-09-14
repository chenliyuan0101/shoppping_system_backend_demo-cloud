package com.mall.usercenter.controller;

import com.mall.usercenter.support.ApiResponse;
import com.mall.usercenter.support.MemberId;
import com.mall.usercenter.support.PageQuery;
import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.dto.NotificationReadAllVO;
import com.mall.usercenter.dto.NotificationReadVO;
import com.mall.usercenter.dto.NotificationUnreadVO;
import com.mall.usercenter.dto.NotificationVO;
import com.mall.usercenter.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内消息（登录态）。
 *
 * <p>消息不是业务接口直接写的：支付/发货/退款完成会投递 MQ 领域事件，
 * 由消费者写入 {@code ums_notification}（见《后端RabbitMQ使用手册.md》§领域事件）。
 * 因此**接口返回是最终一致的**：支付成功到消息可见通常只差几十毫秒。
 */
@Tag(name = "用户-站内消息")
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "我的消息分页(按时间倒序)")
    @GetMapping("/page")
    public ApiResponse<PageResult<NotificationVO>> page(
            @MemberId Long memberId,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(notificationService.page(memberId, page.getPageNum(), page.getPageSize()));
    }

    @Operation(summary = "未读消息数(前台角标)")
    @GetMapping("/unread-count")
    public ApiResponse<NotificationUnreadVO> unreadCount(@MemberId Long memberId) {
        long unread = notificationService.unreadCount(memberId);
        NotificationUnreadVO vo = new NotificationUnreadVO();
        vo.setUnread(unread);
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "标记单条消息已读")
    @PostMapping("/{id}/read")
    public ApiResponse<NotificationReadVO> markRead(@MemberId Long memberId, @PathVariable long id) {
        boolean updated = notificationService.markRead(memberId, id);
        NotificationReadVO vo = new NotificationReadVO();
        vo.setUpdated(updated);
        return ApiResponse.ok(vo);
    }

    @Operation(summary = "全部标记已读")
    @PostMapping("/read-all")
    public ApiResponse<NotificationReadAllVO> markAllRead(@MemberId Long memberId) {
        int updated = notificationService.markAllRead(memberId);
        NotificationReadAllVO vo = new NotificationReadAllVO();
        vo.setUpdated(updated);
        return ApiResponse.ok(vo);
    }
}
