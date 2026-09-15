package com.mall.review.comment;

import com.mall.review.client.MemberSnapshotClient;
import com.mall.review.support.ReviewTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.MemberId;

/**
 * <b>P4 批次 3 的核心验收</b>：评价的 4 个 HTTP 端点整体搬到本服务后，
 * <b>提交链路全本地</b>且对外契约（路径/入参/JSON 字段/错误码/文案）与改造前逐字一致。
 *
 * <h2>为什么是"真库 + 真 HTTP 面"的用例</h2>
 * 提交的成败完全由三条 SQL 的语义决定（读模型的等值查询、本地闸门的条件更新影响行数、
 * 评价表的 INSERT），任何"假 DB / mock mapper"的用例都证明不了闸门这件事。
 * 因此本套件连的是真的 {@code mall_review}，并且<b>不</b>用 {@code @Transactional} 回滚测试事务：
 * 抢闸门要求 UPDATE 真的落到别的连接/别的快照上，"同一个未提交事务里自说自话"证明不了并发语义。
 * 代价是必须自己清理（见 {@link #cleanUp()}）。
 *
 * <h2>覆盖的 8 条 C1 基线文案（逐字断言）</h2>
 * <ol>
 *   <li>400 请填写评价内容（items 为空）</li>
 *   <li>404 订单不存在（订单号未知 / 订单不属于该会员 / 订单号缺失）</li>
 *   <li>409 订单完成后才能评价（读模型里的收货时间还没到）</li>
 *   <li>409 已超过 90 天可评价期限（收货时间早于 now-90d，天数来自配置）</li>
 *   <li>400 评价条目不属于该订单（明细不在该订单里）</li>
 *   <li>400 同一订单明细不能重复评价（同一请求里重复提交同一条明细）</li>
 *   <li>409 该商品已评价（<b>快照预检分支</b>：读模型里 commented=1）</li>
 *   <li>409 该商品已评价（<b>闸门抢占失败分支</b>：条件更新影响 0 行——与第 7 条同文案不同分支）</li>
 * </ol>
 * 第 9 条基线文案（400 评分须为 1~5）同样覆盖。
 *
 * <h2>昵称是怎么被"mock"的（以及为什么这不影响结论）</h2>
 * 昵称快照的唯一外部来源是 user-center 的 {@code /internal/v1/user/member/{id}/snapshot}。
 * 测试里把它替换成 mock，只是为了两件事：
 * <ul>
 *   <li>断言"取到的昵称会被落进 {@code pms_comment.member_nickname} 快照"；</li>
 *   <li>制造"另一个并发请求先抢到闸门"这一瞬间（见 {@link #submit_claimLostByConcurrentClaimer()}）——
 *       用一个确定性的钩子代替不可靠的多线程竞争。</li>
 * </ul>
 * 真实的 HTTP 客户端（含"会员域不可达 → 空串、提交照样成功"）由
 * {@link MemberSnapshotClientTest} 单独覆盖。
 */
class CommentApiMySqlTest extends ReviewTestBase {

    /** 昵称客户端：见类注释——只为断言"快照值来自它"和制造并发抢占 */
    @MockitoBean
    private MemberSnapshotClient memberSnapshotClient;

    /** 本用例造的数据（清理由 {@link #cleanUp()} 完成；ID 刻意远离真实数据） */
    private final List<Long> createdItems = new ArrayList<>();
    private final List<Long> createdComments = new ArrayList<>();
    private final List<String> createdOrderNos = new ArrayList<>();
    private final List<Long> createdSpus = new ArrayList<>();

    /** 每个用例一个全新会员/订单号/明细号：不用事务回滚，就必须靠"命名空间"隔离 */
    private long memberId;
    private long seq;
    private long idSeq;

    @AfterEach
    void cleanUp() {
        for (Long id : createdComments) {
            jdbcTemplate.update("DELETE FROM mall_review.pms_comment WHERE id = ?", id);
        }
        for (Long id : createdItems) {
            jdbcTemplate.update("DELETE FROM mall_review.review_pending_item WHERE order_item_id = ?", id);
        }
        for (String orderNo : createdOrderNos) {
            jdbcTemplate.update("DELETE FROM mall_review.pms_comment WHERE order_no = ?", orderNo);
            jdbcTemplate.update("DELETE FROM mall_review.review_pending_item WHERE order_no = ?", orderNo);
        }
        for (Long spuId : createdSpus) {
            jdbcTemplate.update("DELETE FROM mall_review.pms_comment WHERE spu_id = ?", spuId);
        }
        createdItems.clear();
        createdComments.clear();
        createdOrderNos.clear();
        createdSpus.clear();
    }

    // ==================================================================
    // 1) 快乐路径 + 4 个端点的 JSON 形状
    // ==================================================================

    @Test
    @DisplayName("[提交] 快乐路径：写评价 + 抢闸门 + 昵称快照；4 个端点的 JSON 字段名与文档一致")
    void submitHappyPath_andAllFourEndpointsShape() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long spuId = newSpuId();
        long itemId = pendingItem(orderNo, spuId, 1, LocalDateTime.now().minusDays(1), 0);
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("快照昵称");

        submit(orderNo, item(itemId, 5, "很好用，音质出色", List.of("http://img/a.jpg")))
                .andExpect(status().isOk())               // HTTP 恒 200
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"));

        // ① 评价行真的写了，字段与请求逐字对应，昵称是快照值
        Long commentId = longOf("SELECT id FROM mall_review.pms_comment WHERE order_item_id = ?", itemId);
        createdComments.add(commentId);
        assertEquals(memberId, longOf("SELECT member_id FROM mall_review.pms_comment WHERE id = ?", commentId));
        assertEquals("快照昵称", stringOf("SELECT member_nickname FROM mall_review.pms_comment WHERE id = ?", commentId));
        assertEquals(orderNo, stringOf("SELECT order_no FROM mall_review.pms_comment WHERE id = ?", commentId));
        assertEquals(spuId, longOf("SELECT spu_id FROM mall_review.pms_comment WHERE id = ?", commentId));
        assertEquals(2001L, longOf("SELECT sku_id FROM mall_review.pms_comment WHERE id = ?", commentId));
        assertEquals(5, longOf("SELECT rating FROM mall_review.pms_comment WHERE id = ?", commentId).intValue());
        assertEquals("很好用，音质出色", stringOf("SELECT content FROM mall_review.pms_comment WHERE id = ?", commentId));
        assertEquals(1, longOf("SELECT status FROM mall_review.pms_comment WHERE id = ?", commentId).intValue(),
                "一期直接展示：status 必须恒为 VISIBLE(1)");

        // ② 闸门被抢下，并且记下了真实的评价 id（对账用）
        assertEquals(1, longOf("SELECT commented FROM mall_review.review_pending_item WHERE order_item_id = ?", itemId)
                .intValue(), "提交成功后读模型的 commented 必须被置 1");
        assertEquals(commentId, longOf("SELECT comment_id FROM mall_review.review_pending_item WHERE order_item_id = ?", itemId),
                "comment_id 必须是对应的评价 id（本地闸门与评价行在同一个事务里，两者不可能不一致）");

        // ③ GET /api/comment/product/{spuId}（公开）：CommentsResult{goodRate, comments{...}}
        mockMvc.perform(get("/api/comment/product/" + spuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.goodRate").value(100))
                .andExpect(jsonPath("$.data.comments.total").value(1))
                .andExpect(jsonPath("$.data.comments.pageNum").value(1))
                .andExpect(jsonPath("$.data.comments.pageSize").value(10))
                .andExpect(jsonPath("$.data.comments.list[0].id").value(commentId))
                .andExpect(jsonPath("$.data.comments.list[0].rating").value(5))
                .andExpect(jsonPath("$.data.comments.list[0].content").value("很好用，音质出色"))
                .andExpect(jsonPath("$.data.comments.list[0].images[0]").value("http://img/a.jpg"))
                .andExpect(jsonPath("$.data.comments.list[0].nickname").value("快照昵称"))
                .andExpect(jsonPath("$.data.comments.list[0].createTime").exists());

        // ④ GET /api/product/{spuId}/comments（公开）：**同一个实现**，形状与数据必须完全一致
        MvcResult viaProduct = mockMvc.perform(get("/api/product/" + spuId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.goodRate").value(100))
                .andExpect(jsonPath("$.data.comments.total").value(1))
                .andExpect(jsonPath("$.data.comments.list[0].id").value(commentId))
                .andExpect(jsonPath("$.data.comments.list[0].nickname").value("快照昵称"))
                .andReturn();
        assertEquals(body(mockMvc.perform(get("/api/comment/product/" + spuId)).andReturn()), body(viaProduct),
                "两条读路径必须返回逐字相同的 JSON（R4：只切一半会让同一页面出现两个数字）");

        // ⑤ GET /api/comment/mine（需登录）：PageResult + spuTitle 来自本地快照
        mockMvc.perform(asMember(get("/api/comment/mine"), memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.list[0].id").value(commentId))
                .andExpect(jsonPath("$.data.list[0].spuId").value(spuId))
                .andExpect(jsonPath("$.data.list[0].spuTitle").value("测试商品A"))
                .andExpect(jsonPath("$.data.list[0].rating").value(5))
                .andExpect(jsonPath("$.data.list[0].content").value("很好用，音质出色"))
                .andExpect(jsonPath("$.data.list[0].images").isArray())
                .andExpect(jsonPath("$.data.list[0].createTime").exists());
    }

    // ==================================================================
    // 2) 8 条基线文案逐字断言
    // ==================================================================

    @Test
    @DisplayName("[400] items 为空 → 请填写评价内容（C1 基线 #1）")
    void submit_emptyItems() throws Exception {
        newNamespace();
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");
        withMember(post("/api/comment").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNo\":\"X\",\"items\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请填写评价内容"));
    }

    @Test
    @DisplayName("[404] 订单号未知 → 订单不存在（C1 基线 #2）")
    void submit_unknownOrder() throws Exception {
        newNamespace();
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");
        submit(newOrderNo(), item(1L, 5, "x", null))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    @DisplayName("[404] 订单是别人的 → 同一个「订单不存在」（不泄漏「这单存在但不是你的」）")
    void submit_orderOfAnotherMember() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long itemId = pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().minusDays(1), 0,
                memberId + 1_000_000L);   // 属于**别人**
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");

        submit(orderNo, item(itemId, 5, "x", null))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("订单不存在"));
        assertEquals(0L, countOf("SELECT COUNT(*) FROM mall_review.pms_comment WHERE order_item_id = ?", itemId),
                "归属校验失败不得写出任何评价");
    }

    @Test
    @DisplayName("[409] 收货时间还没到 → 订单完成后才能评价（C1 基线 #3）")
    void submit_orderNotFinished() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        // 读模型只由 order.finished 投影，所以"未完成"在本地表现为"收货时间在未来"
        // （事件时间戳异常 / 两个服务时钟不一致都会造出这种行）。见实现里的注释。
        long itemId = pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().plusHours(2), 0);
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");

        submit(orderNo, item(itemId, 5, "x", null))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("订单完成后才能评价"));
    }

    @Test
    @DisplayName("[409] 超过可评价期限 → 已超过 90 天可评价期限（天数来自本服务的配置，C1 基线 #4）")
    void submit_expiredOrder() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long itemId = pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().minusDays(91), 0);
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");

        submit(orderNo, item(itemId, 5, "x", null))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("已超过 90 天可评价期限"));

        // 边界：正好 89 天前是可评价的（证明"90"这个数字真的被用上了，而不是恒真/恒假）
        String freshOrder = newOrderNo();
        long freshItem = pendingItem(freshOrder, newSpuId(), 1, LocalDateTime.now().minusDays(89), 0);
        submit(freshOrder, item(freshItem, 5, "边界内", null))
                .andExpect(jsonPath("$.code").value(0));
        createdComments.add(longOf("SELECT id FROM mall_review.pms_comment WHERE order_item_id = ?", freshItem));
    }

    @Test
    @DisplayName("[400] 明细不属于该订单 → 评价条目不属于该订单（C1 基线 #5）")
    void submit_itemNotInOrder() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().minusDays(1), 0);
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");

        // 既覆盖"明细 id 完全不存在"，也覆盖"属于另一张订单"
        String otherOrder = newOrderNo();
        long foreignItem = pendingItem(otherOrder, newSpuId(), 1, LocalDateTime.now().minusDays(1), 0);

        submit(orderNo, item(999_999_999L, 5, "x", null))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("评价条目不属于该订单"));
        submit(orderNo, item(foreignItem, 5, "x", null))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("评价条目不属于该订单"));
    }

    @Test
    @DisplayName("[400] 同一请求里重复提交同一条明细 → 同一订单明细不能重复评价（C1 基线 #6）")
    void submit_duplicateItemInSameRequest() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long itemId = pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().minusDays(1), 0);
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");

        // 这一条是"同请求内去重"（400），与"跨请求重复"（409）是**不同的分支**，不得混淆
        String body = "{\"orderNo\":\"" + orderNo + "\",\"items\":["
                + "{\"orderItemId\":" + itemId + ",\"rating\":5,\"content\":\"a\"},"
                + "{\"orderItemId\":" + itemId + ",\"rating\":4,\"content\":\"b\"}]}";
        withMember(post("/api/comment").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("同一订单明细不能重复评价"));

        assertEquals(0L, countOf("SELECT COUNT(*) FROM mall_review.pms_comment WHERE order_item_id = ?", itemId),
                "整批回滚：第一条也不该留下评价");
        assertEquals(0, longOf("SELECT commented FROM mall_review.review_pending_item WHERE order_item_id = ?", itemId)
                .intValue(), "整批回滚：闸门也不该被抢走");
    }

    @Test
    @DisplayName("[400] 评分越界 → 评分须为 1~5（C1 基线 #9 文案之一）")
    void submit_ratingOutOfRange() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long itemId = pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().minusDays(1), 0);
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");

        submit(orderNo, item(itemId, 0, "x", null))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("评分须为 1~5"));
        submit(orderNo, item(itemId, 6, "x", null))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("评分须为 1~5"));
        String nullRating = "{\"orderNo\":\"" + orderNo + "\",\"items\":[{\"orderItemId\":" + itemId + ",\"content\":\"x\"}]}";
        withMember(post("/api/comment").contentType(MediaType.APPLICATION_JSON).content(nullRating))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("评分须为 1~5"));
    }

    @Test
    @DisplayName("[409-A] 快照预检分支：读模型里 commented=1 → 该商品已评价（C1 基线 #7）")
    void submit_alreadyCommentedSnapshot() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long itemId = pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().minusDays(1), 1);   // 已评价
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");

        submit(orderNo, item(itemId, 5, "再来一次", null))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该商品已评价"));
        assertEquals(0L, countOf("SELECT COUNT(*) FROM mall_review.pms_comment WHERE order_item_id = ?", itemId),
                "预检拦下的请求不得写出评价");
    }

    @Test
    @DisplayName("[409-B] 闸门抢占失败分支：条件更新影响 0 行 → 同一个「该商品已评价」，且不留半条评价")
    void submit_claimLostByConcurrentClaimer() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long itemId = pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().minusDays(1), 0);

        // 用昵称客户端的回调扮演"另一个并发请求"：它在**快照读取之后、闸门抢占之前**把这条明细抢走。
        // 这是确定性地命中"抢占失败"分支的唯一办法（真起两个线程会变成随机失败用例），
        // 而且它走的正是真实的路径：另一条连接提交了 commented=1，随后本事务的条件更新影响 0 行。
        when(memberSnapshotClient.nickname(anyLong())).thenAnswer(invocation -> {
            jdbcTemplate.update("UPDATE mall_review.review_pending_item SET commented = 1 WHERE order_item_id = ?", itemId);
            return "并发者";
        });

        submit(orderNo, item(itemId, 5, "抢不到的请求", null))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该商品已评价"));

        assertEquals(0L, countOf("SELECT COUNT(*) FROM mall_review.pms_comment WHERE order_item_id = ?", itemId),
                "抢闸门失败必须整批回滚：先插入的评价行不能留下（否则会出现同一明细两条评价）");
    }

    // ==================================================================
    // 3) 幂等 / 身份 / 降级
    // ==================================================================

    @Test
    @DisplayName("[幂等] 同一明细提交两次：第二次 409「该商品已评价」，且只有一条评价、闸门不会被改写")
    void submit_isIdempotent() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long spuId = newSpuId();
        long itemId = pendingItem(orderNo, spuId, 1, LocalDateTime.now().minusDays(1), 0);
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("快照昵称");

        submit(orderNo, item(itemId, 5, "第一次", null)).andExpect(jsonPath("$.code").value(0));
        Long commentId = longOf("SELECT id FROM mall_review.pms_comment WHERE order_item_id = ?", itemId);
        createdComments.add(commentId);

        submit(orderNo, item(itemId, 4, "第二次", null))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该商品已评价"));

        assertEquals(1L, countOf("SELECT COUNT(*) FROM mall_review.pms_comment WHERE order_item_id = ?", itemId),
                "重复提交绝不能写出第二条评价（R3：闸门是唯一防线，没有唯一键兜底）");
        assertEquals(commentId, longOf("SELECT comment_id FROM mall_review.review_pending_item WHERE order_item_id = ?", itemId),
                "第二次提交不得改写闸门里记下的 comment_id");
        mockMvc.perform(get("/api/comment/product/" + spuId))
                .andExpect(jsonPath("$.data.comments.total").value(1));
    }

    @Test
    @DisplayName("[身份] 没有网关注入凭据 → 401「未登录」；读取路径不受影响（公开）")
    void submit_andMine_requiresGatewayIdentity() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long itemId = pendingItem(orderNo, newSpuId(), 1, LocalDateTime.now().minusDays(1), 0);

        // 提交：未登录（不带头）
        mockMvc.perform(post("/api/comment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"items\":[{\"orderItemId\":" + itemId
                                + ",\"rating\":5,\"content\":\"x\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        // 我的评价：未登录（这一条原来是单体 GatewayIdentityMySqlTest 里的断言，随接口一起搬过来）
        mockMvc.perform(get("/api/comment/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        // 客户端的门都关了：什么都没写进去
        assertEquals(0L, countOf("SELECT COUNT(*) FROM mall_review.pms_comment WHERE order_item_id = ?", itemId));

        // 商品评价是公开路径：无身份也应正常返回
        mockMvc.perform(get("/api/comment/product/" + 1001))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("[降级] 昵称取不到（会员域不可用）→ 提交照样成功，快照落空串，展示回落「匿名用户」")
    void submit_nicknameUnavailable() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long spuId = newSpuId();
        long itemId = pendingItem(orderNo, spuId, 1, LocalDateTime.now().minusDays(1), 0);
        // 不 stub：mock 返回 null，正是"取不到昵称"的最小形态（真实客户端在这种情况下返回 ""）
        when(memberSnapshotClient.nickname(anyLong())).thenReturn(null);

        submit(orderNo, item(itemId, 5, "没有昵称也要能评价", null))
                .andExpect(jsonPath("$.code").value(0));

        Long commentId = longOf("SELECT id FROM mall_review.pms_comment WHERE order_item_id = ?", itemId);
        createdComments.add(commentId);
        assertEquals("", stringOf("SELECT member_nickname FROM mall_review.pms_comment WHERE id = ?", commentId),
                "取不到昵称时快照落空串（列是 NOT NULL DEFAULT ''）");
        // 空快照的展示兜底必须与改造前一致（那时是「会员域查不到昵称」）
        mockMvc.perform(get("/api/comment/product/" + spuId))
                .andExpect(jsonPath("$.data.comments.list[0].nickname").value("匿名用户"));
    }

    // ==================================================================
    // 4) 从单体搬过来的读路径用例（PortalBrandMySqlTest#commentsOnSeedProduct）
    // ==================================================================

    @Test
    @DisplayName("[读] 商品评价分页：仅展示中的评价 + 好评率 + 昵称快照（原单体 PortalBrandMySqlTest 的断言）")
    void productComments_onlyVisibleAndGoodRate() throws Exception {
        newNamespace();
        long spuId = newSpuId();   // 刻意用一条没有任何评价的 SPU：断言才是确定的
        createdSpus.add(spuId);
        // 直接插入两条评价（会员 1=demo）：5 星展示 + 1 星隐藏 —— 与单体那条用例逐字相同
        jdbcTemplate.update("INSERT INTO mall_review.pms_comment (member_id, member_nickname, order_no, order_item_id,"
                        + " spu_id, sku_id, rating, content, images, status, deleted, create_time) "
                        + "VALUES (1, '演示会员', 'ORDER-TEST', 1, ?, 2001, 5, '很好用', NULL, 1, 0, NOW())", spuId);
        jdbcTemplate.update("INSERT INTO mall_review.pms_comment (member_id, member_nickname, order_no, order_item_id,"
                        + " spu_id, sku_id, rating, content, images, status, deleted, create_time) "
                        + "VALUES (1, '演示会员', 'ORDER-TEST2', 2, ?, 2001, 1, '隐藏评价', NULL, 2, 0, NOW())", spuId);

        // 改造前这条用例断言的是 nickname="演示会员"——那时昵称是**跨域查会员域**得到的；
        // 现在同一个值必须来自 member_nickname 快照列（R4：昵称快照是硬需求，不是优化）
        mockMvc.perform(get("/api/product/" + spuId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.goodRate").value(100))
                .andExpect(jsonPath("$.data.comments.total").value(1))
                .andExpect(jsonPath("$.data.comments.list[0].rating").value(5))
                .andExpect(jsonPath("$.data.comments.list[0].nickname").value("演示会员"));

        // 另一条读路径同源同值
        mockMvc.perform(get("/api/comment/product/" + spuId))
                .andExpect(jsonPath("$.data.goodRate").value(100))
                .andExpect(jsonPath("$.data.comments.total").value(1))
                .andExpect(jsonPath("$.data.comments.list[0].nickname").value("演示会员"));
    }

    @Test
    @DisplayName("[读] 分页参数收敛：pageSize 超大被钳到 50、pageNum 非法被钳到 1（改造前的口径）")
    void productComments_pageClamp() throws Exception {
        newNamespace();
        long spuId = newSpuId();
        mockMvc.perform(get("/api/comment/product/" + spuId)
                        .param("pageNum", "0").param("pageSize", "100000"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.goodRate").value(100))       // 没有评价时好评率是 100（口径不变）
                .andExpect(jsonPath("$.data.comments.total").value(0))
                .andExpect(jsonPath("$.data.comments.pageNum").value(1))
                .andExpect(jsonPath("$.data.comments.pageSize").value(50))
                .andExpect(jsonPath("$.data.comments.list").isEmpty());
    }

    @Test
    @DisplayName("[读] 我的评价：spuTitle 来自本地读模型快照；未登录 401")
    void mine_usesLocalTitleSnapshot() throws Exception {
        newNamespace();
        String orderNo = newOrderNo();
        long spuId = newSpuId();
        long itemId = pendingItem(orderNo, spuId, 1, LocalDateTime.now().minusDays(1), 0, "快照标题");
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("N");
        submit(orderNo, item(itemId, 4, "内容", null)).andExpect(jsonPath("$.code").value(0));
        createdComments.add(longOf("SELECT id FROM mall_review.pms_comment WHERE order_item_id = ?", itemId));

        mockMvc.perform(asMember(get("/api/comment/mine"), memberId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].spuTitle").value("快照标题"));

        // 别人的"我的评价"里看不到它
        mockMvc.perform(asMember(get("/api/comment/mine"), memberId + 777L))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ==================== helpers ====================

    /** 每个用例换一套全新的 id（不用事务回滚，就靠这个保证互不干扰、也不碰真实数据） */
    private void newNamespace() {
        seq = System.nanoTime() % 1_000_000_000L;
        idSeq = 0;
        memberId = 7_700_000_000L + seq;
        when(memberSnapshotClient.nickname(anyLong())).thenReturn("默认昵称");
    }

    private long newItemId() {
        return 7_100_000_000_000L + seq * 1000 + (idSeq++);
    }

    private String newOrderNo() {
        String orderNo = "P4C3-" + seq + "-" + createdOrderNos.size();
        createdOrderNos.add(orderNo);
        return orderNo;
    }

    private long newSpuId() {
        return 7_900_000_000L + seq * 1000 + (idSeq++);
    }

    /** 造一条待评价读模型行（order.finished 事件的投影结果在测试里的等价物） */
    private long pendingItem(String orderNo, long spuId, int quantity, LocalDateTime finishedTime, int commented) {
        return pendingItem(orderNo, spuId, quantity, finishedTime, commented, memberId, "测试商品A");
    }

    private long pendingItem(String orderNo, long spuId, int quantity, LocalDateTime finishedTime,
                             int commented, long ownerMemberId) {
        return pendingItem(orderNo, spuId, quantity, finishedTime, commented, ownerMemberId, "测试商品A");
    }

    private long pendingItem(String orderNo, long spuId, int quantity, LocalDateTime finishedTime,
                             int commented, String spuTitle) {
        return pendingItem(orderNo, spuId, quantity, finishedTime, commented, memberId, spuTitle);
    }

    private long pendingItem(String orderNo, long spuId, int quantity, LocalDateTime finishedTime,
                             int commented, long ownerMemberId, String spuTitle) {
        long itemId = newItemId();
        jdbcTemplate.update("INSERT INTO mall_review.review_pending_item (order_item_id, order_no, member_id, spu_id,"
                        + " sku_id, spu_title, sku_image, quantity, finished_time, commented, comment_id)"
                        + " VALUES (?, ?, ?, ?, 2001, ?, 'http://img/a.png', ?, ?, ?, NULL)",
                itemId, orderNo, ownerMemberId, spuId, spuTitle, quantity, finishedTime, commented);
        createdItems.add(itemId);
        return itemId;
    }

    /** POST /api/comment：带上网关注入的身份头（等价于"网关验签后转发的请求"） */
    private ResultActions submit(String orderNo, String itemJson) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"items\":[" + itemJson + "]}";
        return withMember(post("/api/comment").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions withMember(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(asMember(builder, memberId));
    }

    private static String item(long orderItemId, Integer rating, String content, List<String> images) {
        StringBuilder sb = new StringBuilder("{\"orderItemId\":").append(orderItemId);
        if (rating != null) {
            sb.append(",\"rating\":").append(rating);
        }
        if (content != null) {
            sb.append(",\"content\":\"").append(content).append("\"");
        }
        if (images != null) {
            sb.append(",\"images\":[").append(images.stream().map(i -> "\"" + i + "\"").reduce((a, b) -> a + "," + b)
                    .orElse("")).append("]");
        }
        return sb.append("}").toString();
    }
}
