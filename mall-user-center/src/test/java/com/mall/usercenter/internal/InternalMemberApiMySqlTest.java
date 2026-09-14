package com.mall.usercenter.internal;

import com.jayway.jsonpath.JsonPath;
import com.mall.usercenter.support.UserCenterTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3-1 的验收测试：**服务连的是自己的库（mall_user），而不是单体的 mall**。
 *
 * <p>这一批不改任何行为（单体仍是唯一入口），所以能证的东西就三件：
 * <ol>
 *   <li>数据确实搬过来了（行数与源库一致、抽样字段一致）；</li>
 *   <li>服务的数据源指向 {@code mall_user}（用 {@code SELECT DATABASE()} 直接证明，
 *       而不是"看配置猜"——配置写对了但 profile 没生效是最容易犯的错）；</li>
 *   <li>内部接口 fail-closed（没令牌/令牌错 → 403），且**带正确令牌能真取到数据**
 *       （P1 的教训：只断言 403 会被"端点写错"骗过）。</li>
 * </ol>
 */
class InternalMemberApiMySqlTest extends UserCenterTestBase {

    @Test
    @DisplayName("[P3-1] 本服务的数据源是 mall_user（不是单体的 mall）")
    void usesOwnSchema() {
        assertEquals("mall_user", schemaName(),
                "user-center 必须连自己的库；连到 mall 说明拆库没生效（那会让 C2 变成一句空话）");
        Long members = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ums_member", Long.class);
        assertTrue(members != null && members > 0, "mall_user.ums_member 应该有搬迁过来的会员数据");
    }

    @Test
    @DisplayName("[内部接口] 没带令牌 → 403，且不返回数据")
    void rejectsWithoutToken() throws Exception {
        mockMvc.perform(get("/internal/v1/user/member/count"))
                .andExpect(status().isOk())          // HTTP 恒 200，业务码在 body
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("[内部接口] 令牌错误 → 403")
    void rejectsWrongToken() throws Exception {
        mockMvc.perform(get("/internal/v1/user/member/count").header(TOKEN_HEADER, "definitely-wrong"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("[内部接口] 令牌正确 → 会员总数与本库 COUNT(*) 一致")
    void memberCount() throws Exception {
        Long expected = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ums_member WHERE deleted = 0", Long.class);

        MvcResult r = mockMvc.perform(get("/internal/v1/user/member/count").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Number actual = JsonPath.read(body(r), "$.data");
        assertEquals(expected.longValue(), actual.longValue(),
                "接口返回的会员数必须等于本库的真实行数（逻辑删除的不算）");
    }

    @Test
    @DisplayName("[内部接口] 会员状态快照：字段齐全；不存在的会员 → data 为 null")
    void memberStatus() throws Exception {
        Long memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM ums_member WHERE deleted = 0 ORDER BY id LIMIT 1", Long.class);

        mockMvc.perform(get("/internal/v1/user/member/" + memberId + "/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.memberId").value(memberId))
                .andExpect(jsonPath("$.data.nickname").exists())
                .andExpect(jsonPath("$.data.status").isNumber());

        // 不存在 → data null（缓存刷新器据此写"不存在"的负缓存，而不是当成查询失败）
        mockMvc.perform(get("/internal/v1/user/member/999999999/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
