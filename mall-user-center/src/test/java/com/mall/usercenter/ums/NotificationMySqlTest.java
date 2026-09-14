package com.mall.usercenter.ums;

import com.mall.usercenter.domain.Notification;
import com.mall.usercenter.service.NotificationService;
import com.mall.usercenter.support.UserCenterTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 站内消息（{@code ums_notification}）真库测试：**读接口**的契约 + 幂等写入。
 *
 * <p><b>P3-5 之后</b>：MQ 消费者已经在本服务（{@code mq.NotificationEventConsumer}，独立队列
 * {@code mall.user.notification}），它调用的正是这里的 {@link NotificationService#push}；
 * 本套件<b>不开 MQ</b>（测试期默认 {@code mall.mq.enabled=false}），直接调 push 造数据——
 * 断言的是"幂等键 (member_id,type,biz_no) 生效"这条不变量与读接口契约；
 * "事件真的能从交换机走到库里"由 {@code NotificationEventMqMySqlTest}（真 MQ）负责。
 */
class NotificationMySqlTest extends UserCenterTestBase {

    @Autowired
    private NotificationService notificationService;

    private long memberId;
    private long otherMemberId;

    @BeforeEach
    void setUp() {
        memberId = insertMember("uc_noti_");
        otherMemberId = insertMember("uc_noti_");
    }

    @AfterEach
    void cleanUp() {
        deleteMember(memberId);
        deleteMember(otherMemberId);
    }

    @Test
    @DisplayName("[MySQL] push 幂等：同一 (member,type,bizNo) 只落一条")
    void pushIsIdempotent() {
        String orderNo = "T" + System.nanoTime();
        String type = Notification.TYPE_ORDER_PAID;

        assertTrue(notificationService.push(memberId, type, "支付成功", "订单已支付", orderNo));
        // 重复投递（at-least-once）：唯一键 (member_id,type,biz_no) + ON DUPLICATE KEY UPDATE id=id 挡掉第二行
        notificationService.push(memberId, type, "支付成功", "订单已支付", orderNo);

        assertEquals(1L, notificationRows(memberId, type, orderNo),
                "重复事件只应产生一条消息（幂等靠数据库唯一键，不是靠先查后插）");
        assertEquals(1L, notificationService.unreadCount(memberId));

        // 同 bizNo 但不同类型 = 另一条消息（唯一键含 type）
        assertTrue(notificationService.push(memberId, Notification.TYPE_ORDER_SHIPPED, "已发货", "包裹已发出", orderNo));
        assertEquals(2L, notificationService.page(memberId, 1, 10).getTotal());
    }

    @Test
    @DisplayName("[MySQL] 消息列表/未读数/标记已读/全部已读 + 越权标记返回 false")
    void notificationEndpoints() throws Exception {
        notificationService.push(memberId, Notification.TYPE_ORDER_PAID, "支付成功", "订单已支付", "T1");
        notificationService.push(memberId, Notification.TYPE_ORDER_SHIPPED, "已发货", "包裹已发出", "T2");
        Long firstId = jdbcTemplate.queryForObject(
                "SELECT id FROM ums_notification WHERE member_id = ? ORDER BY id LIMIT 1", Long.class, memberId);

        // 列表：字段形状（id/type/title/content/bizNo/isRead/createTime）
        mockMvc.perform(asMember(get("/api/notification/page"), memberId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].id").isNumber())
                .andExpect(jsonPath("$.data.list[0].type").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].title").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].content").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].bizNo").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].isRead").value(0))
                .andExpect(jsonPath("$.data.list[0].createTime").isNotEmpty());

        mockMvc.perform(asMember(get("/api/notification/unread-count"), memberId))
                .andExpect(jsonPath("$.data.unread").value(2));

        // 标记单条已读：第二次为 false（幂等）
        mockMvc.perform(asMember(post("/api/notification/" + firstId + "/read"), memberId))
                .andExpect(jsonPath("$.data.updated").value(true));
        mockMvc.perform(asMember(post("/api/notification/" + firstId + "/read"), memberId))
                .andExpect(jsonPath("$.data.updated").value(false));

        // 越权：别人的消息 → updated=false（不抛 404，不泄漏消息是否存在）
        mockMvc.perform(asMember(post("/api/notification/" + firstId + "/read"), otherMemberId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.updated").value(false));

        // 全部已读：只影响自己的未读
        mockMvc.perform(asMember(post("/api/notification/read-all"), memberId))
                .andExpect(jsonPath("$.data.updated").value(1));
        mockMvc.perform(asMember(get("/api/notification/unread-count"), memberId))
                .andExpect(jsonPath("$.data.unread").value(0));
    }

    @Test
    @DisplayName("[MySQL] 未登录访问站内消息 → 401")
    void requireLogin() throws Exception {
        mockMvc.perform(get("/api/notification/page")).andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/notification/unread-count")).andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/notification/read-all")).andExpect(jsonPath("$.code").value(401));
    }

    private long notificationRows(long memberId, String type, String bizNo) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ums_notification WHERE member_id = ? AND type = ? AND biz_no = ?",
                Long.class, memberId, type, bizNo);
        return n == null ? 0L : n;
    }
}
