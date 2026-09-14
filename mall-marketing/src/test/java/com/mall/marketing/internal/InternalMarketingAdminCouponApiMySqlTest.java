package com.mall.marketing.internal;

import com.jayway.jsonpath.JsonPath;
import com.mall.marketing.client.UserCenterMemberClient;
import com.mall.marketing.support.MarketingTestBase;
import com.mall.marketing.support.dto.MemberBriefVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>P5 步骤 C 的后台端点验收</b>：券模板的建/改/启停/删/分页/发放记录搬进营销域后，
 * 后台的**5 条校验文案与错误码逐字不变**（对外契约），且行为（已发放只改期、已发放不可删）与单体一致。
 *
 * <h2>为什么后台规则要在这里测（而不是单体）</h2>
 * 后台规则的属主是营销域；单体只剩"薄转发 + 透传"。因此：
 * <ul>
 *   <li>本套件测**规则本体**（真库）；</li>
 *   <li>单体侧只测"透传"（code/文案不被包成 500，见 {@code AdminCouponForwardMySqlTest}）。</li>
 * </ul>
 *
 * <h2>会员信息来源</h2>
 * {@code records} 要显示"谁领的券"，会员信息来自 user-center 的
 * {@code POST /internal/v1/user/member/batch}。测试里把 {@link UserCenterMemberClient} 换成 mock：
 * 只断言"取到的用户名/昵称被落进响应"，**不**在单测里真连 user-center
 * （真实的跨进程调用由活体脚本守）。这与 review 对 {@code MemberSnapshotClient} 的处理同一套路。
 */
class InternalMarketingAdminCouponApiMySqlTest extends MarketingTestBase {

    /** 会员域客户端：只为断言"records 里的用户名/昵称来自它" */
    @MockitoBean
    private UserCenterMemberClient userCenterMemberClient;

    private MockHttpServletRequestBuilder postJson(String path, String json) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder) {
        return builder.header(TOKEN_HEADER, TOKEN);
    }

    /** 一个合法的建券请求体（门槛 10000 / 减免 2000 / 固定时间段） */
    private String createBody(String name) {
        return "{\"name\":\"" + name + "\",\"type\":1,\"thresholdAmount\":10000,\"discountAmount\":2000,"
                + "\"totalCount\":5,\"perMemberLimit\":1,\"validType\":1,"
                + "\"validStartTime\":\"2026-01-01T00:00:00\",\"validEndTime\":\"2030-12-31T23:59:59\"}";
    }

    private long createTemplate(String name) throws Exception {
        MvcResult r = mockMvc.perform(withToken(postJson(
                        "/internal/v1/marketing/admin/coupon/create", createBody(name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Number id = JsonPath.read(body(r), "$.data");
        long templateId = id.longValue();
        trackTemplate(templateId);
        return templateId;
    }

    // ==================================================================
    // 鉴权（7 个端点都 fail-closed）
    // ==================================================================

    @Test
    @DisplayName("[鉴权] 7 个后台内部端点无令牌 → 403（fail-closed）")
    void allAdminEndpointsRejectWithoutToken() throws Exception {
        String[] paths = {"/page", "/create", "/1/update", "/1/enable", "/1/disable", "/1/delete", "/1/records"};
        for (String path : paths) {
            mockMvc.perform(postJson("/internal/v1/marketing/admin/coupon" + path, "{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    // ==================================================================
    // page / create：形状与字段名
    // ==================================================================

    @Test
    @DisplayName("[page] 带令牌 → 返回券模板实体，JSON 字段名与单体后台逐字一致")
    void pageReturnsEntityShape() throws Exception {
        String name = "后台测试券_" + memberId;
        long templateId = createTemplate(name);

        MvcResult r = mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/page",
                        "{\"keyword\":\"" + name + "\",\"pageNum\":1,\"pageSize\":10}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.list[0].id").value(templateId))
                .andExpect(jsonPath("$.data.list[0].name").value(name))
                .andExpect(jsonPath("$.data.list[0].type").value(1))
                .andExpect(jsonPath("$.data.list[0].thresholdAmount").value(10000))
                .andExpect(jsonPath("$.data.list[0].discountAmount").value(2000))
                .andExpect(jsonPath("$.data.list[0].totalCount").value(5))
                .andExpect(jsonPath("$.data.list[0].receivedCount").value(0))
                .andExpect(jsonPath("$.data.list[0].perMemberLimit").value(1))
                .andExpect(jsonPath("$.data.list[0].validType").value(1))
                .andExpect(jsonPath("$.data.list[0].validStartTime").value("2026-01-01T00:00:00"))
                .andExpect(jsonPath("$.data.list[0].validEndTime").value("2030-12-31T23:59:59"))
                .andExpect(jsonPath("$.data.list[0].status").value(0))
                .andExpect(jsonPath("$.data.list[0].createTime").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].updateTime").isNotEmpty())
                .andReturn();
        assertTrue(body(r).contains("\"validDays\":null"), "券模板实体的字段一个都不能少（含 null 的）：" + body(r));
    }

    @Test
    @DisplayName("[create] 校验文案逐字：券名/减免额/门槛 三条 DTO 级 + 时间格式")
    void createValidationMessages() throws Exception {
        // 券名缺失（@NotBlank「请输入券名称」）
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/create",
                        "{\"type\":1,\"thresholdAmount\":10000,\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026-01-01T00:00:00\"}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入券名称"));

        // 减免额缺失/为 0（@NotNull+@Min「减免金额必须大于 0」）
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/create",
                        "{\"name\":\"x\",\"type\":1,\"thresholdAmount\":10000,\"discountAmount\":0,\"validType\":1,"
                                + "\"validStartTime\":\"2026-01-01T00:00:00\"}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("减免金额必须大于 0"));

        // 门槛为负（@NotNull+@Min「门槛金额不能为负」）
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/create",
                        "{\"name\":\"x\",\"type\":1,\"thresholdAmount\":-1,\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026-01-01T00:00:00\"}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("门槛金额不能为负"));

        // 时间格式错误（基线文案「时间格式错误，示例 yyyy-MM-ddTHH:mm:ss」）
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/create",
                        "{\"name\":\"x\",\"type\":1,\"thresholdAmount\":10000,\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026/01/01 00:00:00\"}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("时间格式错误，示例 yyyy-MM-ddTHH:mm:ss"));
    }

    @Test
    @DisplayName("[create] 业务校验文案逐字：减免>门槛 / 暂仅支持满减券 / 固定时间段需填开始时间")
    void createBusinessRuleMessages() throws Exception {
        // 减免金额不能大于门槛金额
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/create",
                        "{\"name\":\"x\",\"type\":1,\"thresholdAmount\":1000,\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026-01-01T00:00:00\"}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("减免金额不能大于门槛金额"));

        // 暂仅支持满减券（type=2）
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/create",
                        "{\"name\":\"x\",\"type\":2,\"thresholdAmount\":10000,\"discountAmount\":2000,\"validType\":1,"
                                + "\"validStartTime\":\"2026-01-01T00:00:00\"}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("暂仅支持满减券"));

        // 固定时间段类型需填写开始时间
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/create",
                        "{\"name\":\"x\",\"type\":1,\"thresholdAmount\":10000,\"discountAmount\":2000,\"validType\":1}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("固定时间段类型需填写开始时间"));
    }

    // ==================================================================
    // update / enable / disable / delete
    // ==================================================================

    @Test
    @DisplayName("[update] 未发放：整单更新；已发放：**只允许改有效期**（名称不变）")
    void updateOnlyValidityWhenIssued() throws Exception {
        long templateId = createTemplate("未发放券_" + memberId);
        // 未发放 → 可以改名
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/" + templateId + "/update",
                        "{\"name\":\"改过名的券\",\"type\":1,\"thresholdAmount\":20000,\"discountAmount\":3000,"
                                + "\"validType\":1,\"validStartTime\":\"2026-01-01T00:00:00\","
                                + "\"validEndTime\":\"2030-12-31T23:59:59\"}")))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("改过名的券", stringOf(
                "SELECT name FROM mall_marketing.sms_coupon WHERE id = ?", templateId));
        assertEquals(3000, intOf("SELECT discount_amount FROM mall_marketing.sms_coupon WHERE id = ?", templateId));

        // 造"已发放"（received_count > 0）→ 名称/门槛/减免都不应生效，只有有效期生效
        jdbcTemplate.update("UPDATE mall_marketing.sms_coupon SET received_count = 1 WHERE id = ?", templateId);
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/" + templateId + "/update",
                        "{\"name\":\"不应生效改名\",\"type\":1,\"thresholdAmount\":10000,\"discountAmount\":2000,"
                                + "\"validEndTime\":\"2031-12-31T23:59:59\"}")))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("改过名的券", stringOf(
                "SELECT name FROM mall_marketing.sms_coupon WHERE id = ?", templateId), "已发放的模板不得被改名");
        assertEquals(3000, intOf("SELECT discount_amount FROM mall_marketing.sms_coupon WHERE id = ?", templateId),
                "已发放的模板不得改减免额");
        assertEquals("2031-12-31 23:59:59", stringOf(
                "SELECT CAST(valid_end_time AS CHAR) FROM mall_marketing.sms_coupon WHERE id = ?", templateId),
                "已发放的模板**允许**改有效期（这是它唯一的可改项）");
    }

    @Test
    @DisplayName("[enable/disable] 停用/启用真的落库；模板不存在 → 404「券模板不存在」")
    void enableDisableAndMissingTemplate() throws Exception {
        long templateId = createTemplate("启停券_" + memberId);

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/" + templateId + "/disable", "{}")))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, templateStatusOf(templateId), "停用必须落库 status=1");

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/" + templateId + "/enable", "{}")))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, templateStatusOf(templateId), "启用必须落库 status=0");

        // 不存在的模板：404 + 文案逐字（【券模板不存在】是后台基线文案）
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/9199999999/disable", "{}")))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("券模板不存在"));
    }

    @Test
    @DisplayName("[delete] 未发放可删；已发放 → 409「该券已有人领取，无法删除(可停用)」（逐字）")
    void deleteOnlyWhenNotIssued() throws Exception {
        long deletable = createTemplate("待删券_" + memberId);
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/" + deletable + "/delete", "{}")))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0L, countOf("SELECT COUNT(*) FROM mall_marketing.sms_coupon WHERE id = ?", deletable),
                "未发放的模板必须真的被删掉");

        long issued = createTemplate("已发放券_" + memberId);
        jdbcTemplate.update("UPDATE mall_marketing.sms_coupon SET received_count = 1 WHERE id = ?", issued);
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/" + issued + "/delete", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该券已有人领取，无法删除(可停用)"));
        assertEquals(1L, countOf("SELECT COUNT(*) FROM mall_marketing.sms_coupon WHERE id = ?", issued),
                "被拒绝的删除不得真的删掉模板");
    }

    // ==================================================================
    // records：会员信息来自 user-center，couponStatus 走 3→1 投影
    // ==================================================================

    @Test
    @DisplayName("[records] 会员用户名/昵称取自 user-center 契约；锁定中的券投影成 1")
    void recordsUseMemberContractAndProjection() throws Exception {
        long templateId = createTemplate("记录券_" + memberId);
        // 两条领取记录：一条未使用(0)、一条锁定中(3) —— 后者必须投影成 1
        long unused = newCouponMember(templateId, memberId, 0, LocalDateTime.now().plusDays(7), null, null);
        long locked = newCouponMember(templateId, memberId + 7L, 3, LocalDateTime.now().plusDays(7),
                "T-LOCK", null);

        MemberBriefVO brief = new MemberBriefVO();
        brief.setId(memberId);
        brief.setUsername("limit_user");
        brief.setNickname("限流测试会员");
        MemberBriefVO brief2 = new MemberBriefVO();
        brief2.setId(memberId + 7L);
        brief2.setUsername("locked_user");
        brief2.setNickname("锁定测试会员");
        when(userCenterMemberClient.members(any(Collection.class))).thenReturn(List.of(brief, brief2));

        MvcResult r = mockMvc.perform(withToken(postJson(
                        "/internal/v1/marketing/admin/coupon/" + templateId + "/records",
                        "{\"pageNum\":1,\"pageSize\":10}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn();
        String body = body(r);

        // 会员信息是**跨进程**取回来的（不是直连 ums_member，那张表在会员域的库里）
        assertTrue(body.contains("limit_user") && body.contains("限流测试会员"),
                "records 必须带上 user-center 返回的用户名/昵称：" + body);
        assertTrue(body.contains("locked_user"), "两条记录都要补上会员信息：" + body);

        // 三态投影：库里 3 → 对外 1；库里 0 → 对外 0
        // ⚠️ 这里刻意**不用** JsonPath 的 `$.data.list[?(@.id==N)].couponStatus[0]` 过滤表达式：
        // 券 id 是 9.6e11 量级（超过 int），JsonPath 的数字比较在这种量级上不可靠
        // （实测：过滤结果为空数组 → ClassCastException JSONArray→Integer）。
        // 改成读成 List<Map> 后按 long 比较，语义一样但不受 JsonPath 数字类型影响。
        List<Map<String, Object>> rows = JsonPath.read(body, "$.data.list");
        Map<String, Object> lockedRow = rowOf(rows, locked);
        Map<String, Object> unusedRow = rowOf(rows, unused);
        assertEquals(1, ((Number) lockedRow.get("couponStatus")).intValue(),
                "后台记录页也必须把锁定的券显示成'已使用'（与 /api/coupon/mine 同口径）");
        assertEquals(0, ((Number) unusedRow.get("couponStatus")).intValue());
        assertEquals("locked_user", lockedRow.get("memberUsername"));
        assertEquals("锁定测试会员", lockedRow.get("memberNickname"));
        assertTrue(body.contains("\"memberUsername\"") && body.contains("\"memberNickname\""),
                "字段名必须与单体后台逐字一致：" + body);
    }

    /** 按 id（long 比较）从 records 的 list 里取那一行 */
    private Map<String, Object> rowOf(List<Map<String, Object>> rows, long id) {
        return rows.stream()
                .filter(m -> ((Number) m.get("id")).longValue() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("records 里找不到 id=" + id + " 的记录：" + rows));
    }

    @Test
    @DisplayName("[records] 模板不存在 → 404「券模板不存在」（先校验模板再查记录）")
    void recordsRequireTemplate() throws Exception {
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/admin/coupon/9199999999/records", "{}")))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("券模板不存在"));
    }
}
