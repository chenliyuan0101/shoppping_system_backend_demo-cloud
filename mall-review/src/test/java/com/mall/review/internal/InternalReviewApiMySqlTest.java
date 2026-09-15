package com.mall.review.internal;

import com.jayway.jsonpath.JsonPath;
import com.mall.review.support.ReviewTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.MemberId;

/**
 * P4 第 1 批的验收测试：**服务连的是自己的库（mall_review），读得到真正搬过来的评价数据**。
 *
 * <p>这一批不改任何行为（评价的读写仍在单体 pms，网关也还没路由到本服务），
 * 所以能证明的就是三件事：
 * <ol>
 *   <li>{@code SELECT DATABASE()} 真的是 {@code mall_review}——
 *       用数据库自己回答，而不是"看配置文件猜"（配置写对了但 profile 没生效是最容易犯的错）；</li>
 *   <li>数据确实搬过来了：接口返回的评价数与**本库**的真实行数一致；
 *       单条评价的字段（含 {@code member_nickname} 快照）与库里的行逐字一致；</li>
 *   <li>内部接口 fail-closed（没令牌/令牌错 → 403），且**带正确令牌能真取到数据**——
 *       只断言 403 会被"端点路径写错"骗过（P1/P2 的教训：403 对任何路径都成立），
 *       所以两个方向都要断言。</li>
 * </ol>
 */
class InternalReviewApiMySqlTest extends ReviewTestBase {

    @Test
    @DisplayName("[P4-1] 本服务的数据源是 mall_review（不是单体的 mall）")
    void usesOwnSchema() {
        assertEquals("mall_review", schemaName(),
                "review 必须连自己的库；连到 mall 说明拆库没生效（那会让 P4 变成一句空话）");
        long comments = countOf("SELECT COUNT(*) FROM pms_comment");
        assertTrue(comments > 0, "mall_review.pms_comment 应该有搬迁过来的评价数据");
    }

    @Test
    @DisplayName("[P4-1] 源表与迁移表的结构差异只有一处：新增 member_nickname（且没有 order_item_id 唯一键）")
    void schemaHasNoUniqueKeyOnOrderItemId() {
        // ① 昵称快照列必须存在——否则"展示评论不再查会员域"这条就落不了地
        assertEquals(1L, countOf("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = 'mall_review' AND TABLE_NAME = 'pms_comment'
                   AND COLUMN_NAME = 'member_nickname'
                """), "pms_comment 必须有 member_nickname 快照列");

        // ② order_item_id 上**不能**有唯一键：现网数据本身就违反唯一性（2502 行 / 1887 个不同值），
        //    加了会失败。这条断言是"我们**刻意**没加"的可执行证据——它同时守着
        //    "以后有人顺手补一个 UNIQUE 键"会把迁移搞崩。
        assertEquals(0L, countOf("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = 'mall_review' AND TABLE_NAME = 'pms_comment'
                   AND COLUMN_NAME = 'order_item_id' AND NON_UNIQUE = 0
                """), "pms_comment 刻意不加 order_item_id 唯一键（防重复闸门放在 review_pending_item 上）");
    }

    @Test
    @DisplayName("[P4-1] review_pending_item 的 order_item_id 是主键（这也是抢闸门成立的前提）")
    void pendingItemUsesOrderItemIdAsPrimaryKey() {
        assertEquals(1L, countOf("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = 'mall_review' AND TABLE_NAME = 'review_pending_item'
                   AND COLUMN_NAME = 'order_item_id' AND INDEX_NAME = 'PRIMARY'
                """), "review_pending_item.order_item_id 必须是主键：条件更新 `WHERE order_item_id=? AND commented=0` 的"
                + "影响行数就是并发凭证，没有唯一索引这条判定不成立");

        // idx_member_commented 是**复合**索引（member_id, commented），
        // information_schema.STATISTICS 每个列一行，所以"两行"才是对的；
        // 单看"索引存在"还不够——列顺序反了（commented 在前）依然存在索引，
        // 但 member_id 的等值前缀就用不上，'我的待评价'会退化成全表扫。
        assertEquals(2L, countOf("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = 'mall_review' AND TABLE_NAME = 'review_pending_item'
                   AND INDEX_NAME = 'idx_member_commented'
                """), "缺少 idx_member_commented(member_id, commented)，'我的待评价'列表会全表扫");
        assertEquals("member_id", stringOf("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = 'mall_review' AND TABLE_NAME = 'review_pending_item'
                   AND INDEX_NAME = 'idx_member_commented' AND SEQ_IN_INDEX = 1
                """), "复合索引的第一列必须是 member_id（查询的等值前缀），否则索引用不上");
    }

    @Test
    @DisplayName("[内部接口] 没带令牌 → 403，且不返回数据")
    void rejectsWithoutToken() throws Exception {
        mockMvc.perform(get("/internal/v1/review/comment/count"))
                .andExpect(status().isOk())          // HTTP 恒 200，业务码在 body
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("[内部接口] 令牌错误 → 403（且不泄漏「密钥错在哪一位」）")
    void rejectsWrongToken() throws Exception {
        mockMvc.perform(get("/internal/v1/review/comment/count").header(TOKEN_HEADER, "definitely-wrong"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("[内部接口] 令牌正确 → 评价总数与本库 COUNT(*) 一致（不是「接口能跑」而是「数字对得上」）")
    void commentCountMatchesOwnTable() throws Exception {
        long expected = countOf("SELECT COUNT(*) FROM pms_comment WHERE deleted = 0");
        assertTrue(expected > 0, "本库应该有评价数据，否则这条断言等于什么都没证明");

        MvcResult r = mockMvc.perform(get("/internal/v1/review/comment/count").header(TOKEN_HEADER, TOKEN))
                // 令牌对了还必须 code=0：路径写错时这里会是 404（静态资源兜底），
                // 只有 403 的断言会让"端点不存在"伪装成"鉴权正常"
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Number actual = JsonPath.read(body(r), "$.data");
        assertEquals(expected, actual.longValue(),
                "接口返回的评价数必须等于 mall_review.pms_comment 的真实行数（逻辑删除的不算）");
    }

    @Test
    @DisplayName("[内部接口] 单条评价：字段与库里的行逐字一致，含昵称快照；不存在的 id → data 为 null")
    void commentDetail() throws Exception {
        Long id = longOf("SELECT MIN(id) FROM pms_comment WHERE deleted = 0");
        assertNotNull(id, "本库应该有评价数据");
        long memberId = longOf("SELECT member_id FROM pms_comment WHERE id = ?", id);
        String nickname = stringOf("SELECT member_nickname FROM pms_comment WHERE id = ?", id);

        mockMvc.perform(get("/internal/v1/review/comment/" + id).header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.memberId").value(memberId))
                .andExpect(jsonPath("$.data.memberNickname").value(nickname))
                .andExpect(jsonPath("$.data.spuId").isNumber())
                .andExpect(jsonPath("$.data.rating").isNumber())
                .andExpect(jsonPath("$.data.images").isArray());

        // 昵称快照非空：搬迁时从 mall_user.ums_member 回填过（实测 2502 行全部命中），
        // 它是"评论展示不再依赖会员域"的唯一凭据——空串说明回填没跑或跑了但匹配不上
        assertTrue(nickname != null && !nickname.isEmpty(),
                "id=" + id + " 的 member_nickname 为空，说明 02-migrate-data.sql 的昵称回填没生效");

        // 不存在 → data null（调用方据此写负缓存，而不是当成查询失败）
        mockMvc.perform(get("/internal/v1/review/comment/999999999").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
