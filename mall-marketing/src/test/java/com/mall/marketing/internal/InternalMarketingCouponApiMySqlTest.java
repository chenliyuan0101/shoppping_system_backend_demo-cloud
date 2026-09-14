package com.mall.marketing.internal;

import com.jayway.jsonpath.JsonPath;
import com.mall.marketing.support.MarketingTestBase;
import com.mall.marketing.support.constant.CouponMemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>5 个内部接口的 HTTP 面验收</b>（{@code /internal/v1/marketing/coupon/{usable,discount,lock,use,unlock}}）。
 *
 * <h2>为什么"双向"都必须断言</h2>
 * 只验"无令牌 → 403"会被**端点写错**骗过（P1 实测踩过：漏写一个端点后请求落到
 * {@code {id}} 变量上，403 照样成立，直到有人带令牌调才发现 500）。因此每个端点都有两条断言：
 * <ol>
 *   <li>没令牌 / 令牌错 → {@code code=403}，且 {@code data} 为空（fail-closed）；</li>
 *   <li>带正确令牌 → {@code code=0} 且 <b>数据与库里真实行逐字一致</b>
 *       （本批的"服务读的是自己的库"就靠这一条）。</li>
 * </ol>
 *
 * <h2>HTTP 恒 200</h2>
 * 所有断言都先 {@code status().isOk()} 再看 body 里的 {@code code}：
 * 这正是本项目的契约（{@code ApiResponse}）。若哪天有人把 409 改成真正的 HTTP 409，
 * trade 侧的"按 code 判成败"会立刻失灵——这条断言就是守它的。
 */
class InternalMarketingCouponApiMySqlTest extends MarketingTestBase {

    private String orderNo(String suffix) {
        return "T" + memberId + "-" + suffix;
    }

    private MockHttpServletRequestBuilder postJson(String path, String json) {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder) {
        return builder.header(TOKEN_HEADER, TOKEN);
    }

    // ==================================================================
    // ① 鉴权（两个方向）
    // ==================================================================

    @Test
    @DisplayName("[鉴权] 5 个端点无令牌 → 403，且不返回任何数据（fail-closed）")
    void allEndpointsRejectWithoutToken() throws Exception {
        String body = "{\"memberId\":1,\"goodsTotal\":100}";
        for (String path : new String[]{"/usable", "/discount", "/lock", "/use", "/unlock"}) {
            mockMvc.perform(postJson("/internal/v1/marketing/coupon" + path, body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    @DisplayName("[鉴权] 令牌错误 → 403（不区分'没带'与'带错'，不泄漏密钥对了几位）")
    void allEndpointsRejectWrongToken() throws Exception {
        mockMvc.perform(postJson("/internal/v1/marketing/coupon/usable", "{\"memberId\":1,\"goodsTotal\":100}")
                        .header(TOKEN_HEADER, "definitely-wrong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ==================================================================
    // ② usable：带令牌能取到真数据
    // ==================================================================

    @Test
    @DisplayName("[usable] 带令牌 → code=0，列表与库里的可用券逐字一致（不是'接口能跑'，是'数据对'）")
    void usableReturnsRealRows() throws Exception {
        long templateId = newTemplateWithThreshold(2000L, 500L);
        long couponId = newCouponMember(templateId);
        // 干扰项：锁定中的券不得出现在可用券里
        newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.LOCKED,
                LocalDateTime.now().plusDays(7), orderNo("X0"), null);

        MvcResult r = mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/usable",
                        "{\"memberId\":" + memberId + ",\"goodsTotal\":3000}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))     // 令牌对了还必须 0：路径写错时这里会是 404
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(couponId))
                .andExpect(jsonPath("$.data[0].name").value(stringOf(
                        "SELECT name FROM mall_marketing.sms_coupon WHERE id = ?", templateId)))
                .andExpect(jsonPath("$.data[0].discountAmount").value(500))
                .andExpect(jsonPath("$.data[0].thresholdAmount").value(2000))
                .andReturn();
        assertNotNull(body(r));

        // 金额不够时同一张券不可用（门槛过滤也在服务端，不是前端筛的）
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/usable",
                        "{\"memberId\":" + memberId + ",\"goodsTotal\":1999}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ==================================================================
    // ③ discount：抵扣额 + 5 条文案（HTTP 逐字）
    // ==================================================================

    @Test
    @DisplayName("[discount] 带令牌 → 返回抵扣额；面额大于商品金额时封顶到商品金额")
    void discountReturnsCappedAmount() throws Exception {
        long couponId = newCouponMember(newTemplate(5000L));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/discount",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId + ",\"goodsTotal\":3000}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(3000));

        // 不用券（couponMemberId 缺失/null）→ 0，**不是** 400
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/discount",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":null,\"goodsTotal\":3000}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    @DisplayName("[discount 文案] 5 条校验文案在 HTTP 面上逐字一致")
    void discountMessagesAreVerbatim() throws Exception {
        // ① 不是本人的券 → 400 优惠券不可用
        long othersCoupon = newCouponMember(newTemplate(500L), memberId + 777L,
                CouponMemberStatus.UNUSED, LocalDateTime.now().plusDays(7), null, null);
        assertDiscountError(othersCoupon, 3000L, 400, "优惠券不可用");

        // ② 已使用 → 409 优惠券已被使用或失效
        long usedCoupon = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.USED,
                LocalDateTime.now().plusDays(7), orderNo("Y0"), LocalDateTime.now().minusHours(1));
        assertDiscountError(usedCoupon, 3000L, 409, "优惠券已被使用或失效");

        // ②' 锁定中（三态新增）→ 同一条 409
        long lockedCoupon = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.LOCKED,
                LocalDateTime.now().plusDays(7), orderNo("Y1"), null);
        assertDiscountError(lockedCoupon, 3000L, 409, "优惠券已被使用或失效");

        // ③ 已过期 → 400 优惠券已过期
        long expiredCoupon = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.UNUSED,
                LocalDateTime.now().minusMinutes(1), null, null);
        assertDiscountError(expiredCoupon, 3000L, 400, "优惠券已过期");

        // ④ 模板停用 → 400 优惠券已停用
        long disabledCoupon = newCouponMember(newDisabledTemplate(500L));
        assertDiscountError(disabledCoupon, 3000L, 400, "优惠券已停用");

        // ⑤ 未达门槛 → 400 未满足优惠券使用门槛
        long thresholdCoupon = newCouponMember(newTemplateWithThreshold(5000L, 500L));
        assertDiscountError(thresholdCoupon, 3000L, 400, "未满足优惠券使用门槛");
    }

    private void assertDiscountError(long couponId, long goodsTotal, int code, String message) throws Exception {
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/discount",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                                + ",\"goodsTotal\":" + goodsTotal + "}")))
                .andExpect(status().isOk())          // HTTP 恒 200，业务码在 body
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ==================================================================
    // ④ lock / use / unlock：HTTP 面与库里的真值
    // ==================================================================

    @Test
    @DisplayName("[lock] 带令牌 → locked=true 且库里真的是 3 + order_no；重复 lock 幂等 true")
    void lockOverHttp() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("Z1");
        String json = "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                + ",\"orderNo\":\"" + no + "\"}";

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock", json)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.locked").value(true));

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId), "HTTP 面成功后库里必须真的是锁定中");
        assertEquals(no, dbOrderNoOf(couponId));

        // 同一订单重复 lock → 幂等 true（双击提交/网关重试）
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock", json)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.locked").value(true));
        assertEquals(no, dbOrderNoOf(couponId));
    }

    @Test
    @DisplayName("[lock 冲突] 被别的订单锁住 → HTTP 200 + code=409 + 文案逐字；库里归属不变")
    void lockConflictOverHttp() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String first = orderNo("Z2");
        String second = orderNo("Z3");

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                                + ",\"orderNo\":\"" + first + "\"}")))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                                + ",\"orderNo\":\"" + second + "\"}")))
                .andExpect(status().isOk())                       // ★ HTTP 仍然 200
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("优惠券已被使用或失效"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertEquals(first, dbOrderNoOf(couponId), "冲突方不得抢走别人的锁定");
    }

    @Test
    @DisplayName("[lock 参数] 订单号缺失/为空 → 400（把'锁出一张谁都解不开的券'挡在库外）")
    void lockWithoutOrderNoIsRejected() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId + "}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("订单号不能为空"));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId + ",\"orderNo\":\"  \"}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("订单号不能为空"));

        assertEquals(0, dbStatusOf(couponId), "参数非法时一次库都不该写");
    }

    @Test
    @DisplayName("[lock 参数] 请求体不是 JSON / 缺字段 → 400，不是 500")
    void lockWithBadBodyIsRejected() throws Exception {
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock", "not-json")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock", "{}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("会员ID不能为空"));
    }

    @Test
    @DisplayName("[use] 带令牌 → changed=true 且库里 3→1；重复 use 幂等 true（use_time 不变）")
    void useOverHttp() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("Z4");
        String json = "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                + ",\"orderNo\":\"" + no + "\"}";
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock", json)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/use", json)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changed").value(true));

        assertEquals(CouponMemberStatus.USED, dbStatusOf(couponId));
        LocalDateTime firstUseTime = dateTimeOf(
                "SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId);
        assertNotNull(firstUseTime, "核销必须写 use_time");

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/use", json)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changed").value(true));
        assertEquals(firstUseTime, dateTimeOf(
                        "SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId),
                "重复核销不得刷新 use_time");
    }

    @Test
    @DisplayName("[use] 不是本单锁的券 → code=0 + changed=false（**不是**错误码：trade 只记日志）")
    void useByAnotherOrderReturnsFalseOverHttp() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String lockedBy = orderNo("Z5");
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                                + ",\"orderNo\":\"" + lockedBy + "\"}")))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/use",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                                + ",\"orderNo\":\"" + orderNo("Z6") + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changed").value(false));

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId), "被拒绝的 use 不得改动状态");
    }

    @Test
    @DisplayName("[unlock] 带令牌 → changed=true 且库里回到 0、order_no 清空；重复 unlock 幂等 true")
    void unlockOverHttp() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("Z7");
        String json = "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                + ",\"orderNo\":\"" + no + "\"}";
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock", json)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/unlock", json)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changed").value(true));

        assertEquals(CouponMemberStatus.UNUSED, dbStatusOf(couponId), "取消/超时后券必须真的回到未使用");
        assertNull(dbOrderNoOf(couponId), "解锁必须清空 order_no");

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/unlock", json)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changed").value(true));
    }

    @Test
    @DisplayName("[unlock] 不能解锁别人锁的券 → code=0 + changed=false，状态不变")
    void unlockOthersLockReturnsFalseOverHttp() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String otherOrder = orderNo("Z8");
        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                                + ",\"orderNo\":\"" + otherOrder + "\"}")))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/unlock",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                                + ",\"orderNo\":\"" + orderNo("Z9") + "\"}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changed").value(false));

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId));
        assertEquals(otherOrder, dbOrderNoOf(couponId));
    }

    @Test
    @DisplayName("[契约] 5 个端点的响应都是 {code,message,data}，且 JSON 字段名与规格一致")
    void responseShapeIsStable() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("ZA");
        String json = "{\"memberId\":" + memberId + ",\"couponMemberId\":" + couponId
                + ",\"orderNo\":\"" + no + "\"}";

        MvcResult lock = mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/lock", json)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.locked").exists())
                .andReturn();
        assertEquals(Boolean.TRUE, JsonPath.read(body(lock), "$.data.locked"));

        MvcResult use = mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/use", json)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changed").exists())
                .andReturn();
        assertEquals(Boolean.TRUE, JsonPath.read(body(use), "$.data.changed"));

        MvcResult discount = mockMvc.perform(withToken(postJson("/internal/v1/marketing/coupon/discount",
                        "{\"memberId\":" + memberId + ",\"couponMemberId\":null,\"goodsTotal\":3000}")))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Number amount = JsonPath.read(body(discount), "$.data");
        assertEquals(0L, amount.longValue(), "不用券时 data 是数字 0（不是对象、不是 null）");
    }
}
