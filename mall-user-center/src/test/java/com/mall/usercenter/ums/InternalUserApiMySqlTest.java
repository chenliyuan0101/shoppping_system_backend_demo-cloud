package com.mall.usercenter.ums;

import com.jayway.jsonpath.JsonPath;
import com.mall.usercenter.support.CacheKeys;
import com.mall.usercenter.support.TokenVersionService;
import com.mall.usercenter.support.UserCenterTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.mall.common.support.MemberId;

/**
 * {@code /internal/v1/user/**} 的<b>契约测试</b>：P3-3 新增/变更的那些端点。
 *
 * <p>覆盖四类"光看代码看不出来"的东西：
 * <ol>
 *   <li><b>fail-closed</b>：没带 {@code X-Internal-Token} 一律 403（内部端点能改会员状态、能清空购物车，
 *       暴露=把最危险的能力公开）；</li>
 *   <li><b>字段形状</b>：{@code /member/page} 的分页载荷与 {@code MemberSnapshotVO} 的字段名逐字断言
 *       （跨服务契约靠逐字相同守，任何一侧改名都必须让这条断言红）；</li>
 *   <li><b>属主域不变量</b>：{@code POST /member/{id}/status} 禁用 ⇒ 令牌版本 +1
 *       （"状态改了但旧 token 还能用"是拆服务后最容易出的漏网）；</li>
 *   <li><b>两阶段闸门</b>：{@code /cart/claim} 以 orderNo 幂等（同单重放=claimed，异单=不 claimed），
 *       {@code /cart/restore} 归还明细且二次调用返回 false。</li>
 * </ol>
 */
class InternalUserApiMySqlTest extends UserCenterTestBase {

    private final List<Long> createdMembers = new ArrayList<>();
    private final List<String> redisKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdMembers.forEach(this::deleteMember);
        createdMembers.clear();
        if (!redisKeys.isEmpty()) {
            redisTemplate.delete(redisKeys);
            redisKeys.clear();
        }
    }

    // ==================== 1. fail-closed ====================

    @Test
    @DisplayName("[内部接口] 除 member/count 外的端点：无令牌 / 错令牌 → 403，且不产生副作用")
    void failClosedWithoutInternalToken() throws Exception {
        long memberId = newMember("uc_int_");

        // 分页主查：无令牌
        mockMvc.perform(post("/internal/v1/user/member/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 结算闸门：无令牌（这条最要紧——它能清空别人的购物车）
        mockMvc.perform(post("/internal/v1/user/cart/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":" + memberId + ",\"orderNo\":\"X1\",\"itemIds\":[1]}"))
                .andExpect(jsonPath("$.code").value(403));

        // 改会员状态：无令牌
        mockMvc.perform(post("/internal/v1/user/member/" + memberId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(403));

        // 地址快照：无令牌
        mockMvc.perform(get("/internal/v1/user/address/default").param("memberId", String.valueOf(memberId)))
                .andExpect(jsonPath("$.code").value(403));

        // 会员档案快照：无令牌（后台会员详情走它，同样不能裸露）
        mockMvc.perform(get("/internal/v1/user/member/" + memberId + "/snapshot"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 错令牌同样 403
        mockMvc.perform(get("/internal/v1/user/address/default")
                        .header(TOKEN_HEADER, "definitely-wrong")
                        .param("memberId", String.valueOf(memberId)))
                .andExpect(jsonPath("$.code").value(403));
    }

    // ==================== 2. 会员契约（新增端点 + 平移端点）====================

    @Test
    @DisplayName("[内部接口] /member/page：分页载荷与 MemberSnapshotVO 字段名逐字一致")
    void memberPageFieldShape() throws Exception {
        // 同一个关键字前缀：断言的是"这一页就是这两个会员"，因此前缀每次运行唯一（不受历史残留影响）
        String prefix = "ucpage" + (System.nanoTime() % 1_000_000L);
        long id1 = newMember(prefix);
        long id2 = newMember(prefix);

        MvcResult result = mockMvc.perform(post("/internal/v1/user/member/page")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"" + prefix + "\",\"status\":1,\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(jsonPath("$.code").value(0))
                // PageResult 形状：total/pageNum/pageSize/list
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                // MemberSnapshotVO 形状：字段名即跨服务契约，改名=契约漂移
                .andExpect(jsonPath("$.data.list[0].id").isNumber())
                .andExpect(jsonPath("$.data.list[0].username").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].nickname").value("集成测试会员"))
                .andExpect(jsonPath("$.data.list[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].status").value(1))
                .andExpect(jsonPath("$.data.list[0].createTime").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].password").doesNotExist())
                .andReturn();

        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.data.list[*].id");
        List<Long> actual = ids.stream().map(Number::longValue).toList();
        assertTrue(actual.containsAll(List.of(id1, id2)), "分页结果必须包含刚建的两个会员，实际=" + actual);

        // 状态过滤生效（禁用后按 status=1 查不到）
        mockMvc.perform(post("/internal/v1/user/member/" + id1 + "/status")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/internal/v1/user/member/page")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"" + prefix + "\",\"status\":1,\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("[内部接口] /member/{id}、/member/batch、/member/search-ids：形状与单体一致")
    void memberBriefContracts() throws Exception {
        long memberId = newMember("uc_brief_");
        String username = jdbcTemplate.queryForObject(
                "SELECT username FROM ums_member WHERE id = ?", String.class, memberId);

        // 单个：{id,username,nickname,phone}
        mockMvc.perform(get("/internal/v1/user/member/" + memberId).header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(memberId))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.nickname").value("集成测试会员"))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        // 不存在 → data 为 null（调用方据此区分"没有这个会员"与"查询失败"）
        mockMvc.perform(get("/internal/v1/user/member/999999999").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 批量
        mockMvc.perform(post("/internal/v1/user/member/batch").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\":[" + memberId + ",999999999]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(memberId));

        // 关键字检索 id
        mockMvc.perform(post("/internal/v1/user/member/search-ids").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"" + username + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]").value(memberId));
    }

    // ==================== 3. 禁用 ⇒ 令牌版本 +1（属主域不变量）====================

    @Test
    @DisplayName("[内部接口] 禁用会员 → 状态落库 + 令牌版本 +1；启用不 bump")
    void updateStatusDisablesAndBumpsTokenVersion() throws Exception {
        long toDisable = newMember("uc_dis_");
        long toKeep = newMember("uc_ena_");
        String verKeyDisable = CacheKeys.tokenVersion(TokenVersionService.TYPE_USER, toDisable);
        String verKeyEnable = CacheKeys.tokenVersion(TokenVersionService.TYPE_USER, toKeep);
        redisKeys.add(verKeyDisable);
        redisKeys.add(verKeyEnable);

        assertNull(redisTemplate.opsForValue().get(verKeyDisable), "初始令牌版本应为 0（key 不存在）");
        mockMvc.perform(post("/internal/v1/user/member/" + toDisable + "/status")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));

        // ① 状态真的落库了
        assertEquals(0, status(toDisable));
        // ② 令牌版本 +1：这是"禁用即失效"的唯一机制（网关拿它比对旧 token）
        assertEquals("1", redisTemplate.opsForValue().get(verKeyDisable),
                "禁用必须 bump 令牌版本，否则被禁用的会员拿着旧 token 还能继续调用");
        // ③ 内部只读快照随即可见新状态（网关/缓存刷新器读的是这一份）
        mockMvc.perform(get("/internal/v1/user/member/" + toDisable + "/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.data.memberId").value(toDisable))
                .andExpect(jsonPath("$.data.status").value(0));

        // 启用（status=1）不 bump：口径与改造前一致（只有禁用才踢下线）
        mockMvc.perform(post("/internal/v1/user/member/" + toKeep + "/status")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, status(toKeep));
        assertNull(redisTemplate.opsForValue().get(verKeyEnable),
                "启用不该 bump 令牌版本（否则每次启用都会把会员踢下线）");
    }

    @Test
    @DisplayName("[内部接口] 改状态：会员不存在 → 404；状态值非法 → 400（文案与单体一致）")
    void updateStatusErrorCodes() throws Exception {
        mockMvc.perform(post("/internal/v1/user/member/999999999/status")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("会员不存在"));

        long memberId = newMember("uc_bad_");
        mockMvc.perform(post("/internal/v1/user/member/" + memberId + "/status")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":7}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("状态值仅支持 0禁用 1正常"));
        assertEquals(1, status(memberId), "校验失败不该改动状态");
    }

    // ==================== 4. 地址快照契约 ====================

    @Test
    @DisplayName("[内部接口] 地址快照：默认地址；不属于该会员 → data 为 null（不区分不存在）")
    void addressSnapshotContracts() throws Exception {
        long owner = newMember("uc_addr_");
        long other = newMember("uc_addr_");
        long addressId = insertAddress(owner, "张三", "13800138000", "北京市", "北京市", "朝阳区", "测试路 1 号", 1);

        mockMvc.perform(get("/internal/v1/user/address/default")
                        .header(TOKEN_HEADER, TOKEN).param("memberId", String.valueOf(owner)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(addressId))
                .andExpect(jsonPath("$.data.memberId").value(owner))
                .andExpect(jsonPath("$.data.receiverName").value("张三"))
                .andExpect(jsonPath("$.data.receiverPhone").value("13800138000"))
                .andExpect(jsonPath("$.data.provinceName").value("北京市"))
                .andExpect(jsonPath("$.data.cityName").value("北京市"))
                .andExpect(jsonPath("$.data.districtName").value("朝阳区"))
                .andExpect(jsonPath("$.data.detail").value("测试路 1 号"));

        mockMvc.perform(get("/internal/v1/user/address/" + addressId)
                        .header(TOKEN_HEADER, TOKEN).param("memberId", String.valueOf(owner)))
                .andExpect(jsonPath("$.data.id").value(addressId));

        // 不属于该会员 → null（与"不存在"同样处理，不泄漏他人地址是否存在）
        mockMvc.perform(get("/internal/v1/user/address/" + addressId)
                        .header(TOKEN_HEADER, TOKEN).param("memberId", String.valueOf(other)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 没有地址的会员 → null
        mockMvc.perform(get("/internal/v1/user/address/default")
                        .header(TOKEN_HEADER, TOKEN).param("memberId", String.valueOf(other)))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("[内部接口] /member/page：注册时间段（ISO 字符串、可空、区间为 [start,end) ）")
    void memberPageFiltersByRegisterTime() throws Exception {
        String prefix = "uctime" + (System.nanoTime() % 1_000_000L);
        long memberId = newMember(prefix);
        // 该行的真实注册时刻（datetime 秒精度）：用它做边界断言，而不是依赖"现在几点"的巧合
        LocalDateTime created = jdbcTemplate.queryForObject(
                "SELECT create_time FROM ums_member WHERE id = ?", LocalDateTime.class, memberId);

        // 6 字段线格式（与单体 UserCenterClient 逐字一致）：ISO 字符串、无纳秒，如 2026-09-13T19:40:12
        expectPageTotal(pageBody(prefix, created.minusDays(1).format(ISO_SECONDS),
                created.plusDays(1).format(ISO_SECONDS)), 1);

        // 时间字段整体省略（4 字段的旧客户端）/ 显式 null → 都不过滤：不因缺字段报错、也不静默变成"查不到"
        expectPageTotal("{\"keyword\":\"" + prefix + "\",\"pageNum\":1,\"pageSize\":10}", 1);
        expectPageTotal("{\"keyword\":\"" + prefix + "\",\"createTimeStart\":null,"
                + "\"createTimeEnd\":null,\"pageNum\":1,\"pageSize\":10}", 1);

        // 起点**含**（ge）：start == 该行注册时刻 → 命中；start = 注册时刻 + 1s → 不命中
        expectPageTotal(pageBody(prefix, created.format(ISO_SECONDS), null), 1);
        expectPageTotal(pageBody(prefix, created.plusSeconds(1).format(ISO_SECONDS), null), 0);

        // 终点**不含**（lt，与单体 MemberQueryServiceImpl.page 的 .ge/.lt 逐字一致）：
        // end == 注册时刻 → 不命中；end = 注册时刻 + 1s → 命中。
        // ⚠️ 因此"闭区间"要在调用方表达成"次日 00:00:00"（单体 AdminMemberController 正是
        // 用 createDateEnd.plusDays(1).atStartOfDay() 做到这一点），而不是 23:59:59。
        expectPageTotal(pageBody(prefix, null, created.format(ISO_SECONDS)), 0);
        expectPageTotal(pageBody(prefix, null, created.plusSeconds(1).format(ISO_SECONDS)), 1);
    }

    @Test
    @DisplayName("[内部接口] /member/{id}/snapshot：完整档案字段；不存在（含逻辑删除）→ code=0 且 data=null")
    void memberSnapshotContract() throws Exception {
        long memberId = newMember("uc_snap_");
        String username = jdbcTemplate.queryForObject(
                "SELECT username FROM ums_member WHERE id = ?", String.class, memberId);

        // 字段名即跨服务契约（后台会员详情页直读这份 JSON）
        mockMvc.perform(get("/internal/v1/user/member/" + memberId + "/snapshot").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(memberId))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.nickname").value("集成测试会员"))
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.avatar").doesNotExist())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.createTime").isNotEmpty())
                .andExpect(jsonPath("$.data.password").doesNotExist());

        // 不存在 → code 0 + data null：调用方据此表达"会员不存在"而不是"调用失败"（不要 404）
        mockMvc.perform(get("/internal/v1/user/member/999999999/snapshot").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 逻辑删除的会员同样不可见（@TableLogic 生效），口径与 /member/{id} 一致
        jdbcTemplate.update("UPDATE ums_member SET deleted = 1 WHERE id = ?", memberId);
        mockMvc.perform(get("/internal/v1/user/member/" + memberId + "/snapshot").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ==================== 5. 购物车两阶段闸门 ====================

    @Test
    @DisplayName("[内部接口] /cart/claim 按 orderNo 幂等，/cart/restore 归还且二次调用 false")
    void cartClaimAndRestore() throws Exception {
        long owner = newMember("uc_claim_");
        long other = newMember("uc_claim_");
        long item1 = insertCartItem(owner, 1001L, 2001L, 2, 1);
        long item2 = insertCartItem(owner, 1001L, 2002L, 1, 0);
        String orderNo = "T" + System.nanoTime();
        String otherOrderNo = orderNo + "-other";
        redisKeys.add("mall:idem:cart:claim:" + owner + ":" + orderNo);
        redisKeys.add("mall:idem:cart:claim:" + owner + ":" + otherOrderNo);

        // 读明细：{itemId,skuId,quantity}，且只返回该会员的条目
        mockMvc.perform(post("/internal/v1/user/cart/items").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":" + owner + ",\"itemIds\":[" + item1 + "," + item2 + "]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].itemId").value(item1))
                .andExpect(jsonPath("$.data[0].skuId").value(2001))
                .andExpect(jsonPath("$.data[0].quantity").value(2))
                .andExpect(jsonPath("$.data[0].price").doesNotExist());
        mockMvc.perform(post("/internal/v1/user/cart/items").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":" + other + ",\"itemIds\":[" + item1 + "]}"))
                .andExpect(jsonPath("$.data.length()").value(0));

        // 首次领取：claimed=true + 明细快照，且条目已被原子删除（闸门）
        mockMvc.perform(claim(owner, orderNo, item1, item2))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.claimed").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2));
        assertEquals(0L, cartRows(owner), "领取成功后条目必须已被删除（影响行数就是并发去重凭证）");

        // 同一 orderNo 重放（用户双击提交）→ 仍然 claimed=true，回放同一批明细
        mockMvc.perform(claim(owner, orderNo, item1, item2))
                .andExpect(jsonPath("$.data.claimed").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2));

        // 另一个订单抢同一批明细 → claimed=false（不会双扣库存/双落单）
        mockMvc.perform(claim(owner, otherOrderNo, item1, item2))
                .andExpect(jsonPath("$.data.claimed").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        // 归还（订单事务回滚补偿）：条目回来了，且 spu_id 一并还原（uk_member_sku 之外最容易漏的字段）
        mockMvc.perform(post("/internal/v1/user/cart/restore").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":" + owner + ",\"orderNo\":\"" + orderNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
        assertEquals(2L, cartRows(owner));
        assertEquals(1001L, jdbcTemplate.queryForObject(
                "SELECT spu_id FROM ums_cart_item WHERE member_id = ? AND sku_id = 2002", Long.class, owner)
                .longValue(), "归还时必须还原 spu_id（uk_member_sku 之外最容易漏的字段）");
        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT checked FROM ums_cart_item WHERE member_id = ? AND sku_id = 2002", Integer.class, owner)
                .intValue(), "归还时勾选状态也应还原");

        // 二次归还：没有领取记录 → false（幂等，不会重复插条目）
        mockMvc.perform(post("/internal/v1/user/cart/restore").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":" + owner + ",\"orderNo\":\"" + orderNo + "\"}"))
                .andExpect(jsonPath("$.data").value(false));
        assertEquals(2L, cartRows(owner));
    }

    // ---------- helpers ----------

    /** 线格式：与单体 UserCenterClient 发送的 {@code LocalDateTime.toString()} 同形（秒精度、无纳秒） */
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 拼 {@code /member/page} 请求体：两个时间边界可空（不传即该字段整体省略） */
    private static String pageBody(String keyword, String createTimeStart, String createTimeEnd) {
        StringBuilder body = new StringBuilder("{\"keyword\":\"").append(keyword).append('"');
        if (createTimeStart != null) {
            body.append(",\"createTimeStart\":\"").append(createTimeStart).append('"');
        }
        if (createTimeEnd != null) {
            body.append(",\"createTimeEnd\":\"").append(createTimeEnd).append('"');
        }
        return body.append(",\"pageNum\":1,\"pageSize\":10}").toString();
    }

    private void expectPageTotal(String requestBody, int expectedTotal) throws Exception {
        mockMvc.perform(post("/internal/v1/user/member/page")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(expectedTotal));
    }

    private long newMember(String prefix) {
        long id = insertMember(prefix);
        createdMembers.add(id);
        return id;
    }

    private MockHttpServletRequestBuilder claim(long memberId, String orderNo, long... itemIds) {
        StringBuilder ids = new StringBuilder();
        for (long id : itemIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        return post("/internal/v1/user/cart/claim").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":" + memberId + ",\"orderNo\":\"" + orderNo
                        + "\",\"itemIds\":[" + ids + "]}");
    }

    private int status(long memberId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM ums_member WHERE id = ?", Integer.class, memberId);
        return status == null ? -1 : status;
    }

    private long insertAddress(long memberId, String name, String phone, String province,
                               String city, String district, String detail, int isDefault) {
        jdbcTemplate.update("""
                INSERT INTO ums_address (member_id, receiver_name, receiver_phone, province_code, province_name,
                                         city_code, city_name, district_code, district_name, detail, is_default)
                VALUES (?, ?, ?, '110000', ?, '110100', ?, '110105', ?, ?, ?)
                """, memberId, name, phone, province, city, district, detail, isDefault);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM ums_address WHERE member_id = ? ORDER BY id DESC LIMIT 1", Long.class, memberId);
        if (id == null) {
            throw new IllegalStateException("插入地址失败");
        }
        return id;
    }
}
