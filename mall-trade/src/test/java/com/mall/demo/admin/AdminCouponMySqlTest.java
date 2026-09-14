package com.mall.demo.admin;

import com.mall.demo.common.BusinessException;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.client.MarketingClient;
import com.mall.demo.common.dto.AdminCouponVO;
import com.mall.demo.common.dto.CouponRecordVO;
import com.mall.demo.support.MySqlTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台券管理的**薄转发契约测试**（P5 步骤 C）。
 *
 * <h2>为什么只剩"透传"断言</h2>
 * 后台券规则（建/改/启停/删的 5 条文案、已发放只改期、已发放不可删、records 的会员信息）
 * 的属主已经是营销域，真库覆盖在
 * {@code mall-marketing: com.mall.marketing.internal.InternalMarketingAdminCouponApiMySqlTest}。
 * 单体这一侧要守的是**两件不同的事**：
 * <ol>
 *   <li><b>形状不变</b>：路径/方法/参数/响应 JSON 与改造前逐字一致（前端与《接口文档.md》不用改）；</li>
 *   <li><b>业务码与文案逐字透传</b>：营销域的 400/409 必须原样出现在响应里，
 *       **不能**被包成 500「系统繁忙」——包错的表现是"后台页面从'这条券删不了'变成'系统繁忙'"。</li>
 * </ol>
 * 因此这里把 {@link MarketingClient} 换成 Mockito：断言转发调用与透传，不复制券规则。
 *
 * <p>⚠️ 管理端鉴权仍在单体（P8-2a 起 = **只信网关注入的身份头**）：没有身份头（= 直连端口）时
 * {@code /api/admin/coupon/**} 必须 401「未登录」
 * （这也是它**不**经网关直路由到营销域的原因——营销域没有管理端身份过滤器）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminCouponMySqlTest extends MySqlTestBase {

    @MockitoBean
    private MarketingClient marketingClient;

    // ==================================================================
    // 形状与转发
    // ==================================================================

    @Test
    @DisplayName("[转发] page：分页参数原样转发，响应形状 total/pageNum/pageSize/list 与改造前一致")
    void pageForwardsAndKeepsShape() throws Exception {
        AdminCouponVO coupon = new AdminCouponVO();
        coupon.setId(3L);
        coupon.setName("满 299 减 50");
        coupon.setType(1);
        coupon.setThresholdAmount(29900L);
        coupon.setDiscountAmount(5000L);
        coupon.setTotalCount(100);
        coupon.setReceivedCount(0);
        coupon.setPerMemberLimit(1);
        coupon.setValidType(1);
        coupon.setValidStartTime(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        coupon.setValidEndTime(LocalDateTime.of(2030, 12, 31, 23, 59, 59));
        coupon.setStatus(0);
        when(marketingClient.adminPage(eq("满"), eq(0), eq(2L), eq(5L)))
                .thenReturn(PageResult.of(1, 2, 5, List.of(coupon)));

        mockMvc.perform(get("/api/admin/coupon/page").headers(adminHeaders())
                        .param("keyword", "满").param("status", "0")
                        .param("pageNum", "2").param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(5))
                .andExpect(jsonPath("$.data.list[0].id").value(3))
                .andExpect(jsonPath("$.data.list[0].name").value("满 299 减 50"))
                .andExpect(jsonPath("$.data.list[0].thresholdAmount").value(29900))
                .andExpect(jsonPath("$.data.list[0].discountAmount").value(5000))
                .andExpect(jsonPath("$.data.list[0].receivedCount").value(0))
                .andExpect(jsonPath("$.data.list[0].perMemberLimit").value(1))
                .andExpect(jsonPath("$.data.list[0].validType").value(1))
                .andExpect(jsonPath("$.data.list[0].validStartTime").value("2026-01-01T00:00:00"))
                .andExpect(jsonPath("$.data.list[0].validEndTime").value("2030-12-31T23:59:59"))
                .andExpect(jsonPath("$.data.list[0].status").value(0));

        verify(marketingClient).adminPage("满", 0, 2L, 5L);   // 分页收敛在属主域，本层原样转发
    }

    @Test
    @DisplayName("[转发] create/enable/disable/delete/records：路径与参数原样转发")
    void writeEndpointsForward() throws Exception {
        when(marketingClient.adminCreate(any())).thenReturn(77L);
        when(marketingClient.adminRecords(eq(3L), eq(1L), eq(10L)))
                .thenReturn(PageResult.of(1, 1, 10, List.of(record())));

        MvcResult created = mockMvc.perform(post("/api/admin/coupon").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"转发测试券\",\"type\":1,\"thresholdAmount\":10000,"
                                + "\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026-01-01T00:00:00\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(77))
                .andReturn();
        assertEquals(200, created.getResponse().getStatus());

        mockMvc.perform(put("/api/admin/coupon/3").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名\",\"type\":1,\"thresholdAmount\":10000,"
                                + "\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026-01-01T00:00:00\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(put("/api/admin/coupon/3/disable").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(put("/api/admin/coupon/3/enable").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(delete("/api/admin/coupon/3").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/admin/coupon/3/records").headers(adminHeaders())
                        .param("pageNum", "1").param("pageSize", "10"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].memberUsername").value("limit_user"))
                .andExpect(jsonPath("$.data.list[0].memberNickname").value("限流测试会员"))
                .andExpect(jsonPath("$.data.list[0].couponStatus").value(1));

        verify(marketingClient).adminUpdate(eq(3L), any());
        verify(marketingClient).adminDisable(3L);
        verify(marketingClient).adminEnable(3L);
        verify(marketingClient).adminDelete(3L);
        verify(marketingClient).adminRecords(3L, 1L, 10L);
    }

    // ==================================================================
    // 业务码与文案透传（不能包成 500）
    // ==================================================================

    @Test
    @DisplayName("[透传] 营销域的 409「该券已有人领取，无法删除(可停用)」原样返回（不是 500）")
    void deleteMessagePassesThrough() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(409, "该券已有人领取，无法删除(可停用)"))
                .when(marketingClient).adminDelete(9L);

        mockMvc.perform(delete("/api/admin/coupon/9").headers(adminHeaders()))
                .andExpect(status().isOk())          // HTTP 恒 200，业务码在 body
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该券已有人领取，无法删除(可停用)"));
    }

    @Test
    @DisplayName("[透传] 营销域的 400 校验文案原样返回：减免金额不能大于门槛金额 / 时间格式错误")
    void createMessagesPassThrough() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(400, "减免金额不能大于门槛金额"))
                .when(marketingClient).adminCreate(any());
        mockMvc.perform(post("/api/admin/coupon").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":1,\"thresholdAmount\":1000,"
                                + "\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026-01-01T00:00:00\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("减免金额不能大于门槛金额"));

        org.mockito.Mockito.reset(marketingClient);
        org.mockito.Mockito.doThrow(new BusinessException(400, "时间格式错误，示例 yyyy-MM-ddTHH:mm:ss"))
                .when(marketingClient).adminUpdate(eq(3L), any());
        mockMvc.perform(put("/api/admin/coupon/3").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":1,\"thresholdAmount\":10000,"
                                + "\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026/01/01 00:00:00\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("时间格式错误，示例 yyyy-MM-ddTHH:mm:ss"));
    }

    @Test
    @DisplayName("[鉴权] 管理端鉴权仍在单体：无网关注入身份（= 直连端口）→ 401「未登录」，根本不转发")
    void adminAuthStaysInMonolith() throws Exception {
        mockMvc.perform(get("/api/admin/coupon/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
        org.mockito.Mockito.verifyNoInteractions(marketingClient);
    }

    // ---------- helpers ----------

    private CouponRecordVO record() {
        CouponRecordVO vo = new CouponRecordVO();
        vo.setId(11L);
        vo.setMemberId(901234L);
        vo.setMemberUsername("limit_user");
        vo.setMemberNickname("限流测试会员");
        // 投影后的对外值：库里 3(LOCKED) 在营销域已被投影成 1
        vo.setCouponStatus(1);
        vo.setReceiveTime(LocalDateTime.now().minusDays(1));
        vo.setExpireTime(LocalDateTime.now().plusDays(7));
        return vo;
    }
}
