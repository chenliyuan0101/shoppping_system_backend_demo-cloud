package com.mall.admin.controller;

import com.jayway.jsonpath.JsonPath;
import com.mall.admin.support.BffStubTestBase;
import com.mall.admin.support.DownstreamStubs;
import com.mall.admin.support.DownstreamTestWiring;
import com.mall.admin.support.JsonShape;
import com.mall.admin.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>P7 §4 的核心套件：后台会员列表的"分页主查 + 一次批量补数"（无 N+1）</b>，
 * 外加会员详情/启停的 C1 形状与文案（三个下游全部用环回桩）。
 *
 * <h2>无 N+1 的判据（规格 §4，可执行）</h2>
 * <pre>
 *   第 1 页：user-center 分页 1 次 + trade 批量补数 1 次 = 2 次
 *   第 5 页：user-center 分页 1 次 + trade 批量补数 1 次 = 2 次
 *   ⇒ **远程调用次数与页码无关**（本用例把这两组数字直接打出来）
 * </pre>
 * 并且断言"批量补数的请求体里恰好是**这一页**的 id"——只有次数相等还不够：
 * 一个"对每个 id 各调一次、但只统计最后一次"的实现也能让次数看起来相等（不，那会让 pageSize=5 时是 5 次；
 * 但一个"把整表 id 都拉过来补数"的实现次数也是恒定的 1 次，却同样是错的）。
 * 所以次数 + 请求体内容两条一起断言。
 */
class AdminMemberApiStubTest extends BffStubTestBase {

    private static final String PAGE = "/api/admin/member/page";
    private static final String SUMMARY = "/api/admin/dashboard/summary";

    /** MemberAdminVO 的 10 个字段（C1 契约：与单体逐字一致，含恒为 null 的 commentCount） */
    static final Set<String> MEMBER_KEYS = Set.of("id", "username", "nickname", "phone", "avatar", "status",
            "createTime", "orderCount", "totalPaid", "commentCount");

    private LogCapture logs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void pointAtStubs(DynamicPropertyRegistry registry) {
        DownstreamTestWiring.wireAllToStubs(registry);
    }

    @BeforeEach
    void startLogCapture() {
        logs = LogCapture.on("com.mall.admin");
    }

    @AfterEach
    void stopLogCapture() {
        if (logs != null) {
            logs.close();
        }
    }

    // ==================================================================
    // §4 无 N+1
    // ==================================================================

    @Test
    @DisplayName("[§4 无 N+1] 第 1 页与第 5 页的远程调用次数**相等**（各 2 次：分页主查 1 + 批量补数 1）")
    void page_remoteCallCountIsIndependentOfPageNumber() throws Exception {
        // ---- 第 1 页 ----
        String page1 = fetch(PAGE + "?pageNum=1&pageSize=5");
        int userCallsPage1 = DownstreamStubs.USER.callsTo("/member/page");
        int tradeCallsPage1 = DownstreamStubs.TRADE.callsTo("/member-order-brief/batch");
        int totalPage1 = userCallsPage1 + tradeCallsPage1;
        List<Long> idsPage1 = memberIds(page1);

        // ---- 第 5 页 ----
        String page5 = fetch(PAGE + "?pageNum=5&pageSize=5");
        int userCallsPage5 = DownstreamStubs.USER.callsTo("/member/page") - userCallsPage1;
        int tradeCallsPage5 = DownstreamStubs.TRADE.callsTo("/member-order-brief/batch") - tradeCallsPage1;
        int totalPage5 = userCallsPage5 + tradeCallsPage5;
        List<Long> idsPage5 = memberIds(page5);

        // 原始数字打进 surefire 输出（规格要求"原始日志/计数输出"，不是"看起来很快"）
        System.out.println("[§4 无 N+1] page=1 pageSize=5 → user-center 分页调用=" + userCallsPage1
                + " · trade 批量补数调用=" + tradeCallsPage1 + " · 合计=" + totalPage1
                + " · 本页 id=" + idsPage1);
        System.out.println("[§4 无 N+1] page=5 pageSize=5 → user-center 分页调用=" + userCallsPage5
                + " · trade 批量补数调用=" + tradeCallsPage5 + " · 合计=" + totalPage5
                + " · 本页 id=" + idsPage5);

        assertThat(userCallsPage1).as("分页主查恰好 1 次（分页必须留在属主域）").isEqualTo(1);
        assertThat(tradeCallsPage1).as("批量补数恰好 1 次 —— pageSize=5 时若是 5 次就是 N+1").isEqualTo(1);
        assertThat(totalPage1).as("**判据：远程调用次数与页码无关**").isEqualTo(totalPage5);
        assertThat(userCallsPage5).isEqualTo(1);
        assertThat(tradeCallsPage5).isEqualTo(1);
        assertThat(idsPage1).as("两页必须是不同的会员（否则本用例失去意义）").isNotEqualTo(idsPage5).isNotEmpty();
        assertThat(DownstreamStubs.TRADE.calls())
                .as("trade 一共只被调了 2 次（两次翻页各一次）").isEqualTo(2);
    }

    @Test
    @DisplayName("[§4 批量补数] 批量请求体里恰好是**这一页**的 id（不是整表、不是逐个）")
    void page_batchBodyCarriesExactlyThePageIds() throws Exception {
        String json = fetch(PAGE + "?pageNum=3&pageSize=4");
        List<Long> ids = memberIds(json);

        assertThat(ids).as("桩按 pageNum*100+i 造数据").containsExactly(
                DownstreamStubs.memberIdAt(3, 0), DownstreamStubs.memberIdAt(3, 1),
                DownstreamStubs.memberIdAt(3, 2), DownstreamStubs.memberIdAt(3, 3));
        assertThat(DownstreamStubs.TRADE.callsTo("/member-order-brief/batch")).isEqualTo(1);

        String body = DownstreamStubs.TRADE.lastBody();
        assertThat(body).as("请求体字段名必须与单体契约一致（memberIds）").contains("\"memberIds\"");
        for (Long id : ids) {
            assertThat(body).as("这一页的每个 id 都必须在**同一个**请求体里: id=%s body=%s", id, body)
                    .contains(String.valueOf(id));
        }
        assertThat(DownstreamStubs.TRADE.tokens()).containsExactly(DownstreamTestWiring.INTERNAL_TOKEN);
    }

    @Test
    @DisplayName("[§4] 补数结果按 id 落到每一行；键集合与单体 MemberAdminVO 逐字一致（含 commentCount）")
    void page_enrichesEachRowWithOrderCountAndPaidAmount() throws Exception {
        String json = fetch(PAGE + "?pageNum=2&pageSize=3");

        assertThat(readInt(json, "$.code")).isZero();
        assertThat(readLong(json, "$.data.total")).isEqualTo(DownstreamStubs.MEMBER_PAGE_TOTAL);
        assertThat(readLong(json, "$.data.pageNum")).isEqualTo(2L);
        assertThat(readLong(json, "$.data.pageSize")).isEqualTo(3L);
        assertThat(readKeysOfElement(json, "$.data.list[0]"))
                .as("行键集合必须与单体逐字一致（commentCount 恒为 null 但键必须在）")
                .containsExactlyInAnyOrderElementsOf(MEMBER_KEYS);
        assertThat(JsonPath.parse(json).read("$.data.list[0].commentCount", Object.class))
                .as("评价数属于 mall-review，本批不回填（与单体现状逐字相同）").isNull();
        assertThat(readString(json, "$.data.list[0].createTime"))
                .as("createTime 的序列化格式必须与单体一致（实测单体今天返回 ISO-8601 的 2026-09-14T10:42:11："
                        + "spring.jackson.date-format 不作用于 LocalDateTime）")
                .isEqualTo("2026-01-02T03:04:05");

        List<Long> ids = memberIds(json);
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            assertThat(readLong(json, "$.data.list[" + i + "].orderCount"))
                    .as("orderCount 必须按 id 对应（第 %d 行 id=%d）", i, id)
                    .isEqualTo(DownstreamStubs.orderCountOf(id));
            assertThat(readLong(json, "$.data.list[" + i + "].totalPaid"))
                    .as("totalPaid 必须按 id 对应（第 %d 行 id=%d）", i, id)
                    .isEqualTo(DownstreamStubs.paidOf(id));
        }
    }

    @Test
    @DisplayName("[C1 契约] 分页请求体与单体 UserCenterClient **逐字同形**（含起/止的半开区间换算与内部令牌）")
    void page_forwardsTheSameRequestContractAsMonolith() throws Exception {
        fetch(PAGE + "?pageNum=2&pageSize=5&keyword=demo&status=1"
                + "&createDateStart=2026-09-01&createDateEnd=2026-09-03");

        String body = DownstreamStubs.USER.lastBody();
        assertThat(body)
                .as("字段名/分页原样: %s", body)
                .contains("\"keyword\":\"demo\"")
                .contains("\"status\":1")
                .contains("\"pageNum\":2")
                .contains("\"pageSize\":5");
        assertThat(body)
                .as("日期换算必须与单体一致：起=当天 00:00（含）、止=**次日** 00:00（不含）；"
                        + "少一个 plusDays 会把「选到今天」过滤掉。实际 body=%s", body)
                .contains("\"createTimeStart\":\"2026-09-01T00:00\"")
                .contains("\"createTimeEnd\":\"2026-09-04T00:00\"");
    }

    @Test
    @DisplayName("[§4 降级] 交易域不可用 ⇒ 列表仍 code=0、键集合不变、两项为 null（= 单体列表的常态）+ 1 条 warn")
    void page_tradeDown_keepsListAndNullsEnrichment() throws Exception {
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.NONZERO_CODE);
        logs.clear();

        String json = fetch(PAGE + "?pageNum=1&pageSize=2");

        assertThat(readInt(json, "$.code")).as("补数失败不能把整页打回 500").isZero();
        assertThat(readKeysOfElement(json, "$.data.list[0]"))
                .as("键集合必须不变（值变 null 但键还在）").containsExactlyInAnyOrderElementsOf(MEMBER_KEYS);
        assertThat(JsonPath.parse(json).read("$.data.list[0].orderCount", Object.class)).isNull();
        assertThat(JsonPath.parse(json).read("$.data.list[0].totalPaid", Object.class)).isNull();
        assertThat(readString(json, "$.data.list[0].username")).startsWith("member");
        assertThat(logs.warnMessages()).as("每请求恰好一条 WARN").hasSize(1);
        assertThat(logs.warnMessages().get(0)).contains("会员列表订单补数降级");
    }

    // ==================================================================
    // 详情
    // ==================================================================

    @Test
    @DisplayName("[C1] 详情：形状 + 订单口径补数（orderCount/totalPaid 来自交易域）")
    void detail_ok() throws Exception {
        long memberId = 42L;

        String json = fetch("/api/admin/member/" + memberId);

        assertThat(readInt(json, "$.code")).isZero();
        assertThat(readKeysOfElement(json, "$.data")).containsExactlyInAnyOrderElementsOf(MEMBER_KEYS);
        assertThat(readLong(json, "$.data.id")).isEqualTo(memberId);
        assertThat(readString(json, "$.data.username")).isEqualTo("member42");
        assertThat(readLong(json, "$.data.orderCount")).isEqualTo(DownstreamStubs.DETAIL_ORDER_COUNT);
        assertThat(readLong(json, "$.data.totalPaid")).isEqualTo(DownstreamStubs.DETAIL_PAID_AMOUNT);
        assertThat(readString(json, "$.data.createTime"))
                .as("详情的时间格式与列表同一口径（ISO-8601，与单体实测一致）")
                .isEqualTo("2026-01-02T03:04:05");
        assertThat(DownstreamStubs.USER.callsTo("/snapshot")).isEqualTo(1);
        assertThat(DownstreamStubs.TRADE.callsTo("/member-order-brief")).isEqualTo(1);
    }

    @Test
    @DisplayName("[C1] 详情：会员不存在 ⇒ 404「会员不存在」，HTTP 仍 200、data=null")
    void detail_missingMember_404() throws Exception {
        String json = fetch("/api/admin/member/" + DownstreamStubs.MISSING_MEMBER_ID);

        assertThat(readInt(json, "$.code")).isEqualTo(404);
        assertThat(readString(json, "$.message")).as("文案与单体 AdminMemberServiceImpl 逐字一致").isEqualTo("会员不存在");
        assertThat(JsonPath.parse(json).read("$.data", Object.class)).isNull();
        assertThat(DownstreamStubs.TRADE.calls()).as("会员都不存在就不该再问交易域").isZero();
    }

    // ==================================================================
    // 启停（跨服务写 + 提交后失效）
    // ==================================================================

    @Test
    @DisplayName("[§4/§5.8] 启停成功 ⇒ code=0；看板缓存**立刻换代**（下一次 summary 重新打下游）")
    void updateStatus_ok_andEvictsDashboardCache() throws Exception {
        // 先让看板缓存有内容：第一次取数写缓存，第二次命中缓存
        fetch(SUMMARY);
        fetch(SUMMARY);
        assertThat(DownstreamStubs.TRADE.calls()).as("前置：第二次 summary 命中缓存").isEqualTo(1);
        long generationBefore = dashboardCache.generation();

        MvcResult result = mockMvc.perform(gw(put("/api/admin/member/42/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andReturn();
        String putBody = body(result);

        assertThat(readInt(putBody, "$.code")).isZero();
        assertThat(readString(putBody, "$.message")).isEqualTo("ok");
        assertThat(JsonPath.parse(putBody).read("$.data", Object.class)).isNull();
        assertThat(DownstreamStubs.USER.callsTo("/status"))
                .as("状态写必须走会员域契约（「禁用即失效令牌」的不变量在属主域内完成）").isEqualTo(1);
        assertThat(DownstreamStubs.USER.lastBody()).isEqualTo("{\"status\":0}");

        assertThat(dashboardCache.generation())
                .as("活动失效：写路径必须换代（否则看板最多脏 60 秒）")
                .isEqualTo(generationBefore + 1);
        fetch(SUMMARY);
        assertThat(DownstreamStubs.TRADE.calls()).as("换代后 summary 必须重新取数").isEqualTo(2);
    }

    @Test
    @DisplayName("[C1] 启停：状态为空 ⇒ 400「状态值仅支持 0禁用 1正常」，且**不发**远程调用")
    void updateStatus_nullStatus_400() throws Exception {
        MvcResult result = mockMvc.perform(gw(put("/api/admin/member/42/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(readInt(body(result), "$.code")).isEqualTo(400);
        assertThat(readString(body(result), "$.message"))
                .as("与单体 RemoteMemberAdmin.updateStatus 的文案逐字一致")
                .isEqualTo("状态值仅支持 0禁用 1正常");
        assertThat(DownstreamStubs.USER.calls()).as("本地就能判掉的非法请求不该打下游").isZero();
    }

    @Test
    @DisplayName("[C1] 启停：下游 400/404 的 code 与文案**原样透传**（不许被包成 500）")
    void updateStatus_passesDownstreamErrorsThrough() throws Exception {
        long generationBefore = dashboardCache.generation();

        // 下游对非法的状态值返回 400「状态值仅支持 0禁用 1正常」
        MvcResult invalid = mockMvc.perform(gw(put("/api/admin/member/42/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":" + DownstreamStubs.INVALID_STATUS + "}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(readInt(body(invalid), "$.code")).isEqualTo(400);
        assertThat(readString(body(invalid), "$.message")).isEqualTo("状态值仅支持 0禁用 1正常");

        // 下游对不存在的会员返回 404「会员不存在」
        MvcResult missing = mockMvc.perform(gw(put("/api/admin/member/" + DownstreamStubs.MISSING_MEMBER_ID + "/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(readInt(body(missing), "$.code")).isEqualTo(404);
        assertThat(readString(body(missing), "$.message")).isEqualTo("会员不存在");

        // 两种情况下都不能污染看板缓存（写没成功就不该换代）
        assertThat(dashboardCache.generation()).isEqualTo(generationBefore);
    }

    @Test
    @DisplayName("[§5.8 afterCommit] 外层事务回滚 ⇒ **不**换代（失效不许跑在提交之前）；远程写已经发生（如实记录）")
    void updateStatus_rollbackDoesNotEvict() {
        long generationBefore = dashboardCache.generation();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            adminMemberService.updateStatus(42L, 0);
            status.setRollbackOnly();
        });

        assertThat(dashboardCache.generation())
                .as("afterCommitOrNow 的语义：事务回滚 ⇒ 本地副作用（缓存失效）不执行")
                .isEqualTo(generationBefore);
        assertThat(DownstreamStubs.USER.callsTo("/status"))
                .as("如实记录：跨进程的写**不会**被本地回滚撤销（它已经在会员域落库了）——"
                        + "本用例证明的只是「本地失效不早于提交」，不是「远程写可回滚」")
                .isEqualTo(1);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String fetch(String url) throws Exception {
        MvcResult result = mockMvc.perform(gw(get(url)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    private static List<Long> memberIds(String json) {
        List<Number> raw = JsonPath.read(json, "$.data.list[*].id");
        List<Long> ids = new ArrayList<>();
        for (Number n : raw) {
            ids.add(n.longValue());
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readKeysOfElement(String json, String path) {
        Map<String, Object> map = JsonPath.read(json, path);
        return new java.util.LinkedHashSet<>(map.keySet());
    }

    private static int readInt(String json, String path) {
        return ((Number) JsonPath.read(json, path)).intValue();
    }

    private static long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private static String readString(String json, String path) {
        return JsonPath.read(json, path);
    }
}
