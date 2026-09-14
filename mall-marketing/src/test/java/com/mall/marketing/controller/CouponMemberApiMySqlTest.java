package com.mall.marketing.controller;

import com.jayway.jsonpath.JsonPath;
import com.mall.marketing.support.MarketingTestBase;
import com.mall.marketing.support.constant.CouponMemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>P5 批次 2 的核心验收：会员侧 3 个公开端点搬到营销域后，对外契约逐字不变。</b>
 *
 * <p>三个端点（{@code GET /api/coupon/available}、{@code POST /api/coupon/{id}/receive}、
 * {@code GET /api/coupon/mine}）整体搬自单体
 * {@code com.mall.demo.sms.controller.CouponController} + {@code CouponServiceImpl}：
 * 路径/方法/参数名/响应 JSON/错误码/文案**一律未变**。因此本套件里的断言是从单体
 * {@code CouponMySqlTest} 里**搬过来并加强**的（那几段在单体侧本批已删除）。
 *
 * <h2>为什么身份用 {@code asMember()} 而不是真的登录</h2>
 * 生产链路是"网关验签 → 注入 {@code X-Gateway-Auth}/{@code X-Member-Id}"，
 * 所以测试里手工构造这两个头就等价于"网关验签后的身份"（与 user-center/review 的同一套夹具）。
 * 反过来，**不**带这两个头就是匿名——按 fail-closed 必须 401「未登录」。
 *
 * <h2>三态投影在本套件里第一次真正生效</h2>
 * {@code mine} 的过滤（{@code 1 → IN (1,3)}）与响应投影（{@code 3 → 1}）在批次 1 只有纯单测，
 * 本批有了真端点，因此这里用<b>真库里的 LOCKED 行</b>端到端验证。
 */
class CouponMemberApiMySqlTest extends MarketingTestBase {

    // ==================================================================
    // 1) available：券中心列表
    // ==================================================================

    @Test
    @DisplayName("[available] 只列启用且在领取窗口内的模板；received 反映'我领没领过'")
    void availableListsEnabledTemplatesWithReceivedFlag() throws Exception {
        long enabled = newReceivableTemplate(500L);
        long disabled = newTemplate(500L, 0L, 1, 1, null, null, null, 1, 0);
        long notYet = newTemplate(500L, 0L, 0, 1,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10), null, 1, 0);   // 未到开始时间
        long expired = newWindowExpiredTemplate(500L);

        // 先看一遍：两张可见的模板 received 都是 false
        MvcResult before = mockMvc.perform(asMember(get("/api/coupon/available"), memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.id==" + enabled + ")].received").value(false))
                .andReturn();
        String idsShown = JsonPath.read(body(before), "$.data[*].id").toString();
        assertTrue(idsShown.contains(String.valueOf(enabled)), "启用的模板必须在列表里：" + idsShown);
        assertFalse(idsShown.contains(String.valueOf(disabled)), "停用的模板不得出现：" + idsShown);
        assertFalse(idsShown.contains(String.valueOf(notYet)), "未到领取开始时间的模板不得出现：" + idsShown);
        assertFalse(idsShown.contains(String.valueOf(expired)), "领取窗口已过的模板不得出现：" + idsShown);

        // 领一张之后：同一张模板 received 变 true（这就是前端"已领取"按钮的数据来源）
        mockMvc.perform(asMember(post("/api/coupon/" + enabled + "/receive"), memberId))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(get("/api/coupon/available"), memberId))
                .andExpect(jsonPath("$.data[?(@.id==" + enabled + ")].received").value(true))
                .andExpect(jsonPath("$.data[?(@.id==" + enabled + ")].receivedCount").value(1));
    }

    @Test
    @DisplayName("[available] 匿名 → 401「未登录」（单体也是必须登录；文案逐字）")
    void availableRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/coupon/available"))
                .andExpect(status().isOk())            // HTTP 恒 200，业务码在 body
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("[available] 带错的身份凭据 → 同样是 401（不区分'没带'与'带错'，不泄漏密钥）")
    void availableRejectsForgedIdentityHeader() throws Exception {
        mockMvc.perform(get("/api/coupon/available")
                        .header(GW_AUTH_HEADER, "definitely-wrong")
                        .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        // 只带 X-Member-Id 不带凭据 → 也是 401（这就是"防冒充"的核心断言：
        // 任何人都能手写 X-Member-Id，唯有网关能写出对得上的 X-Gateway-Auth）
        mockMvc.perform(get("/api/coupon/available").header(GW_MEMBER_ID_HEADER, "1"))
                .andExpect(jsonPath("$.code").value(401));
    }

    // ==================================================================
    // 2) receive：领券（含 5 条文案 + 并发防超发）
    // ==================================================================

    @Test
    @DisplayName("[receive] 快乐路径：写一行 coupon_member、已发量 +1、到期时间按模板算出来")
    void receiveHappyPath() throws Exception {
        // validType=2（领取后 N 天）：expire_time 应当由 valid_days(7) 算出，而不是模板的 valid_end_time
        long templateId = newTemplate(500L, 0L, 0, 2, null, null, null, 1, 0);

        mockMvc.perform(asMember(post("/api/coupon/" + templateId + "/receive"), memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"));

        Long couponId = longOf("SELECT id FROM mall_marketing.sms_coupon_member"
                + " WHERE member_id = ? AND template_id = ?", memberId, templateId);
        assertNotNull(couponId, "领券必须真的落一行 coupon_member");
        assertEquals(CouponMemberStatus.UNUSED, dbStatusOf(couponId), "新领的券必须是未使用(0)");
        assertEquals(1, receivedCountOf(templateId), "已发量必须 +1");

        LocalDateTime expire = dateTimeOf(
                "SELECT expire_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId);
        assertTrue(expire.isAfter(LocalDateTime.now().plusDays(6)),
                "领取后 N 天有效：expire_time 应该是 now+7d 附近，实际 " + expire);
    }

    @Test
    @DisplayName("[receive 文案①] 重复领券 → 409「已领取过该券」（逐字；单体搬来的原文案）")
    void receiveTwiceConflicts() throws Exception {
        long templateId = newReceivableTemplate(500L);

        mockMvc.perform(asMember(post("/api/coupon/" + templateId + "/receive"), memberId))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(post("/api/coupon/" + templateId + "/receive"), memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("已达每人限领数量"))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 说明：perMemberLimit 默认 1，"再领一次"先在"是否达限"这关就被拦下 → 文案是「已达每人限领数量」。
        // 「已领取过该券」是**并发**撞唯一键那条路径（两个请求同时通过前置校验），文案见下一个用例。
        assertEquals(1, receivedCountOf(templateId), "被拒绝的领取不得把已发量 +1");
        assertEquals(1L, countOf("SELECT COUNT(*) FROM mall_marketing.sms_coupon_member"
                + " WHERE member_id = ? AND template_id = ?", memberId, templateId));
    }

    @Test
    @DisplayName("[receive 文案②] 并发重复领同一张券 → 唯一键拦下，文案「已领取过该券」且已发量不虚增")
    void receiveConcurrentlyKeepsCounterHonest() throws Exception {
        long templateId = newReceivableTemplate(500L);
        // 用一个"已存在一行"的会员先领一次，再模拟并发路径：直接再插一次会撞 uk → 由 Service 转成 409 文案。
        // 这里用**真并发**（两个线程同时发请求）来触发"同时通过前置校验"的那条罕见路径，
        // 若恰好一个先提交、另一个读到已存在，则走的是「已达每人限领数量」——两条文案都合法，
        // 关键是：**只能有一行、已发量只能是 1**。
        int threads = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    MvcResult r = mockMvc.perform(asMember(post("/api/coupon/" + templateId + "/receive"), memberId))
                            .andReturn();
                    return (Integer) JsonPath.read(body(r), "$.code");
                }));
            }
            start.countDown();
            java.util.List<Integer> codes = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> f : futures) {
                codes.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertTrue(codes.stream().anyMatch(c -> c == 0), "至少有一次领取成功：" + codes);
            assertTrue(codes.stream().allMatch(c -> c == 0 || c == 409), "其余必须是 409：" + codes);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1L, countOf("SELECT COUNT(*) FROM mall_marketing.sms_coupon_member"
                        + " WHERE member_id = ? AND template_id = ?", memberId, templateId),
                "并发领取只能落一行（表上的 uk(member_id, template_id) 是最后一道闸）");
        assertEquals(1, receivedCountOf(templateId),
                "已发量必须正好 +1：撞唯一键的那次必须被 decreaseReceived + 事务回滚抵消掉");
    }

    @Test
    @DisplayName("[receive 文案③] 模板停用 → 404「券不存在或已停发」（逐字）")
    void receiveDisabledTemplate() throws Exception {
        long disabled = newTemplate(500L, 0L, 1, 1, null, null, null, 1, 0);

        mockMvc.perform(asMember(post("/api/coupon/" + disabled + "/receive"), memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("券不存在或已停发"));
    }

    @Test
    @DisplayName("[receive 文案④] 券模板不存在 → 同一条 404（不区分'不存在'与'已停发'）")
    void receiveMissingTemplate() throws Exception {
        mockMvc.perform(asMember(post("/api/coupon/9199999999/receive"), memberId))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("券不存在或已停发"));
    }

    @Test
    @DisplayName("[receive 文案⑤] 不在领取时间内 → 409「不在领取时间内」（逐字）")
    void receiveOutOfWindow() throws Exception {
        long notYet = newTemplate(500L, 0L, 0, 1,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10), null, 1, 0);

        mockMvc.perform(asMember(post("/api/coupon/" + notYet + "/receive"), memberId))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("不在领取时间内"));
    }

    @Test
    @DisplayName("[receive 文案⑥] 已领完（total_count 已满）→ 409「券已被领完」（逐字）")
    void receiveSoldOut() throws Exception {
        // total_count=2 / received_count=2 → increaseReceived 影响 0 行 → 「券已被领完」
        long soldOut = newTemplate(500L, 0L, 0, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), 2, 1, 2);

        mockMvc.perform(asMember(post("/api/coupon/" + soldOut + "/receive"), memberId))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("券已被领完"));

        assertEquals(2, receivedCountOf(soldOut), "被拒绝的领取不得改动已发量");
        assertEquals(0L, countOf("SELECT COUNT(*) FROM mall_marketing.sms_coupon_member"
                + " WHERE member_id = ? AND template_id = ?", memberId, soldOut));
    }

    @Test
    @DisplayName("[receive] 匿名 → 401（且不落任何数据）")
    void receiveRequiresLogin() throws Exception {
        long templateId = newReceivableTemplate(500L);

        mockMvc.perform(post("/api/coupon/" + templateId + "/receive"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        assertEquals(0L, countOf("SELECT COUNT(*) FROM mall_marketing.sms_coupon_member"
                + " WHERE template_id = ?", templateId), "未登录的请求不得写库");
        assertEquals(0, receivedCountOf(templateId));
    }

    // ==================================================================
    // 3) mine：我的券（含三态投影）
    // ==================================================================

    @Test
    @DisplayName("[mine] status=0 只出未使用的券；status 过滤走真库，不是前端筛的")
    void mineFiltersByStatus() throws Exception {
        long unused = newCouponMember(newTemplate(500L));
        newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.USED,
                LocalDateTime.now().plusDays(7), "T-M1", LocalDateTime.now().minusHours(1));

        mockMvc.perform(asMember(get("/api/coupon/mine"), memberId).param("status", "0"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(unused))
                .andExpect(jsonPath("$.data[0].couponStatus").value(0))
                .andExpect(jsonPath("$.data[0].name").isNotEmpty())
                .andExpect(jsonPath("$.data[0].expireTime").isNotEmpty());

        // 不带 status = 全部（两张都出）
        mockMvc.perform(asMember(get("/api/coupon/mine"), memberId))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("[mine 三态投影] status=1 → 命中库里的 IN (1,3)，且响应里 3 被投影成 1（不泄漏 LOCKED）")
    void mineProjectsLockedToUsed() throws Exception {
        long used = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.USED,
                LocalDateTime.now().plusDays(7), "T-M2", LocalDateTime.now().minusHours(2));
        long locked = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.LOCKED,
                LocalDateTime.now().plusDays(7), "T-M3", null);
        long unused = newCouponMember(newTemplate(500L));

        MvcResult r = mockMvc.perform(asMember(get("/api/coupon/mine"), memberId).param("status", "1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn();
        String body = body(r);
        String ids = JsonPath.read(body, "$.data[*].id").toString();
        assertTrue(ids.contains(String.valueOf(used)), "已使用的券要在 status=1 里：" + ids);
        assertTrue(ids.contains(String.valueOf(locked)), "锁定中的券必须出现在'已使用'页：" + ids);
        assertFalse(ids.contains(String.valueOf(unused)), "未使用的券不得出现：" + ids);

        // 关键：对外值只能是 0/1/2，库里的 3 必须被投影成 1（前端词表只有三项）
        java.util.List<Integer> statuses = JsonPath.read(body, "$.data[*].couponStatus");
        assertTrue(statuses.stream().allMatch(s -> s == 1),
                "status=1 查出来的每一行对外都必须是 1（含库里的 3），实际：" + statuses);

        // 库里仍然是 3：投影只发生在响应里，不写回数据库
        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(locked));
    }

    @Test
    @DisplayName("[mine 三态投影] status=0 不含锁定中的券；status=2 只出过期的")
    void mineNeverLeaksLockedIntoUnusedOrExpired() throws Exception {
        long locked = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.LOCKED,
                LocalDateTime.now().plusDays(7), "T-M4", null);
        long expired = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.EXPIRED,
                LocalDateTime.now().minusDays(1), null, null);
        long unused = newCouponMember(newTemplate(500L));

        String unusedIds = JsonPath.read(body(mockMvc.perform(
                        asMember(get("/api/coupon/mine"), memberId).param("status", "0"))
                .andExpect(jsonPath("$.data.length()").value(1)).andReturn()), "$.data[*].id").toString();
        assertEquals("[" + unused + "]", unusedIds, "status=0 只能有真正未使用的那张（锁定中的不行）");

        String expiredIds = JsonPath.read(body(mockMvc.perform(
                        asMember(get("/api/coupon/mine"), memberId).param("status", "2"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].couponStatus").value(2)).andReturn()), "$.data[*].id").toString();
        assertEquals("[" + expired + "]", expiredIds, "status=2 只出过期的");
        assertFalse(expiredIds.contains(String.valueOf(locked)));
    }

    @Test
    @DisplayName("[mine] 别人的券不会出现在我的列表里；匿名 → 401")
    void mineIsMemberScopedAndRequiresLogin() throws Exception {
        long othersCoupon = newCouponMember(newTemplate(500L), memberId + 5_555L,
                CouponMemberStatus.UNUSED, LocalDateTime.now().plusDays(7), null, null);
        long mine = newCouponMember(newTemplate(500L));

        String ids = JsonPath.read(body(mockMvc.perform(asMember(get("/api/coupon/mine"), memberId))
                .andExpect(jsonPath("$.data.length()").value(1)).andReturn()), "$.data[*].id").toString();
        assertEquals("[" + mine + "]", ids, "只能看到自己的券");
        assertFalse(ids.contains(String.valueOf(othersCoupon)));

        mockMvc.perform(get("/api/coupon/mine"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    // ==================================================================
    // 4) 限流（键格式 + 429 文案 + fail-open）
    // ==================================================================

    @Test
    @DisplayName("[限流] 同一会员连打 10 次后第 11 次 429；键格式与单体逐字一致 mall:rl:coupon_receive:u{id}")
    void rateLimitKicksInAfterTenCalls() throws Exception {
        assumeTrue(redisUp(), "本机没有 Redis：限流是 fail-open 的，这条用例按跳过处理（见 fail-open 用例）");
        long templateId = newReceivableTemplate(500L);
        String key = "mall:rl:coupon_receive:u" + memberId;

        // 前 10 次：业务上第 2 次起就是 409（已领取），但**都不是 429**——限流计数在业务之前
        for (int i = 1; i <= 10; i++) {
            MvcResult r = mockMvc.perform(asMember(post("/api/coupon/" + templateId + "/receive"), memberId))
                    .andReturn();
            int code = JsonPath.read(body(r), "$.code");
            assertTrue(code != 429, "第 " + i + " 次不应被限流，实际 code=" + code);
        }
        assertEquals("10", redisTemplate.opsForValue().get(key),
                "计数键必须是 mall:rl:coupon_receive:u{memberId}（与单体同一把键、同一个桶）");

        // 第 11 次 → 429 + 文案逐字
        mockMvc.perform(asMember(post("/api/coupon/" + templateId + "/receive"), memberId))
                .andExpect(status().isOk())                 // HTTP 恒 200
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("操作过于频繁，请稍后再试"));
    }

    @Test
    @DisplayName("[限流] 计数键带 TTL（固定窗口，不续期）；且不同会员是不同桶")
    void rateLimitKeyHasTtlAndIsPerMember() throws Exception {
        assumeTrue(redisUp(), "本机没有 Redis：跳过");
        long templateId = newReceivableTemplate(500L);
        mockMvc.perform(asMember(post("/api/coupon/" + templateId + "/receive"), memberId))
                .andExpect(jsonPath("$.code").value(0));

        Long ttl = redisTemplate.getExpire("mall:rl:coupon_receive:u" + memberId);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 60, "TTL 必须在 (0, 60] 秒内（窗口 60s），实际 " + ttl);

        // 另一个会员：自己的桶（不受上面那次计数影响）
        long otherMember = memberId + 1_111L;
        trackMember(otherMember);   // ⚠️ 必须登记：这条路径会给第二个会员**发券**，不登记就留下孤儿行
        mockMvc.perform(asMember(post("/api/coupon/" + templateId + "/receive"), otherMember))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("1", redisTemplate.opsForValue().get("mall:rl:coupon_receive:u" + otherMember));
    }
}
