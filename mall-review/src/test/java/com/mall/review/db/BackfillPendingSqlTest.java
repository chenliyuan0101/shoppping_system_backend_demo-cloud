package com.mall.review.db;

import com.mall.review.support.ReviewTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>P4-2 的 R1 验收</b>：{@code db/04-backfill-pending.sql} 把"历史已评价"的明细回填进读模型，
 * 使它们永远抢不到闸门。
 *
 * <p>为什么必须回填（不然这个文件就没有意义）：读模型是由 {@code order.finished} 事件<b>从零</b>
 * 投影出来的，历史明细没有事件；而源库的 {@code oms_order_item.comment_status} 本身已经坏了
 * ——实测 2502 条 {@code mall_review.pms_comment} 里 **2500 条**对应的明细 status 仍是 0（只有 2 条是孤儿）。
 * 也就是说改造前那套"跨域 CAS"防重对存量数据已经失效，回填是让新闸门重新有效的唯一办法。
 *
 * <h2>本用例怎么测</h2>
 * 不把断言抄一遍 SQL，而是<b>直接执行脚本里那条 INSERT</b>（从 db/04-backfill-pending.sql 读出来），
 * 再对结果断言——这样"脚本写错了"和"数据不对"是两件能被区分的事，而且脚本改了用例不会失真。
 *
 * <h2>四条不变量（对应 R1 的验收口径）</h2>
 * <ol>
 *   <li><b>distinct order_item_id 计数对齐</b>：源侧"可关联且有评论"的明细数 == 读模型里这些明细
 *       被标记已评价的数量；反向再断言读模型里没有源侧不存在的"已评价"行（回填不得造行）；</li>
 *   <li><b>没有任何一行是"有评论却 commented=0"</b>——这一条就是"历史明细不能再被评价"的可执行定义；</li>
 *   <li><b>{@code comment_id} 必须指向该明细自己的一条真实评论</b>（对账列的语义，不是随便一个 id）；</li>
 *   <li><b>幂等</b>：同一条 INSERT 连跑两次，第二次一行都不动（主键 + 无有意义变化的 ODKU）。</li>
 * </ol>
 *
 * <p>另外把两条<b>刻意不做</b>的事也变成断言：不为孤儿评价造行（它们连订单明细都不存在，
 * 目标表的 NOT NULL 列没有权威来源），以及脚本不删表、不删行（P4 的纪律：回退 = 改网关路由）。
 */
class BackfillPendingSqlTest extends ReviewTestBase {

    /** 脚本路径：surefire 的工作目录是模块根（backend/mall-cloud/mall-review） */
    private static final String SCRIPT_RELATIVE = "db/04-backfill-pending.sql";

    @Test
    @DisplayName("[R1] 回填脚本：可关联的已评价明细全部覆盖，且没有任何一行是「有评论却 commented=0」")
    void backfillCoversEveryCommentedItem() throws IOException {
        String script = readScript();

        // 脚本的纪律：不删表、不删行（本批明确要求"不得 drop 任何表"）
        String lower = script.toLowerCase();
        assertFalse(lower.contains("drop table"), "04-backfill-pending.sql 不得删表（P4 纪律）");
        assertFalse(lower.contains("delete from"), "04-backfill-pending.sql 不得删行（回填只做新增/收敛）");

        // —— 执行脚本里那条 INSERT（幂等；本机已执行过时这次插入 0 行） ——
        String insert = extractInsert(script);
        int affected = jdbcTemplate.update(insert);
        assertTrue(affected >= 0, "回填语句执行失败: affected=" + affected);

        // ① 源侧三个数字必须自洽：总去重数 = 可关联去重数 + 孤儿数
        long srcDistinctAll = countOf("SELECT COUNT(DISTINCT order_item_id) FROM mall_review.pms_comment");
        long srcDistinctResolvable = countOf("""
                SELECT COUNT(DISTINCT c.order_item_id) FROM mall_review.pms_comment c
                  JOIN mall_trade.oms_order_item i ON i.id = c.order_item_id
                """);
        List<Long> orphanItemIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT c.order_item_id FROM mall_review.pms_comment c
                  LEFT JOIN mall_trade.oms_order_item i ON i.id = c.order_item_id
                 WHERE i.id IS NULL
                """, Long.class);
        assertEquals(srcDistinctAll, srcDistinctResolvable + orphanItemIds.size(),
                "源侧自洽性：distinct order_item_id 总数必须等于「可关联的」+「孤儿」");

        // 2026-09-13 实测的快照事实：2502 行评论 / 1887 个不同明细 / 其中 2 个是孤儿（DEMO-ORD-88007、88011）
        // 数字变了说明演示数据被重新生成过——先确认回填范围是否仍然正确，再改这里的期望值
        assertEquals(2, orphanItemIds.size(),
                "实测快照里孤儿评价恰好 2 条（订单与明细都不存在，如 DEMO-ORD-88007）；"
                        + "数字变了要先复核回填范围，再更新这条断言");

        // ② distinct order_item_id 计数对齐：每一条"可关联且有评论"的明细都必须在读模型里且 commented=1
        long covered = countOf("""
                SELECT COUNT(*) FROM (
                    SELECT DISTINCT c.order_item_id AS oid
                      FROM mall_review.pms_comment c
                      JOIN mall_trade.oms_order_item i ON i.id = c.order_item_id) src
                 WHERE EXISTS (SELECT 1 FROM mall_review.review_pending_item p
                                WHERE p.order_item_id = src.oid AND p.commented = 1)
                """);
        assertEquals(srcDistinctResolvable, covered,
                "有 " + (srcDistinctResolvable - covered) + " 条「已有关联评论」的明细没有在读模型里被标记已评价"
                        + "——它们会被新闸门放行，从而被重复评价（这正是 R1 要堵的洞）");

        // 反向：读模型里不许有源侧不存在的"已评价"行（回填不得造行、不得越界）
        assertEquals(0L, countOf("""
                SELECT COUNT(*) FROM mall_review.review_pending_item p
                 WHERE p.commented = 1
                   AND NOT EXISTS (SELECT 1 FROM mall_review.pms_comment c WHERE c.order_item_id = p.order_item_id)
                """), "读模型里出现了源侧没有评论的「已评价」行：回填越界了");

        // ③ 核心不变量：有评论的明细不许是 commented=0
        assertEquals(0L, countOf("""
                SELECT COUNT(*) FROM mall_review.review_pending_item p
                 WHERE p.commented = 0
                   AND EXISTS (SELECT 1 FROM mall_review.pms_comment c WHERE c.order_item_id = p.order_item_id)
                """), "存在「有评论却 commented=0」的行——新闸门会把它放行，历史明细就能被第二次评价");

        // ④ comment_id 必须指向该明细自己的一条真实评论
        assertEquals(0L, countOf("""
                SELECT COUNT(*) FROM mall_review.review_pending_item p
                 WHERE p.commented = 1
                   AND NOT EXISTS (SELECT 1 FROM mall_review.pms_comment c
                                    WHERE c.id = p.comment_id AND c.order_item_id = p.order_item_id)
                """), "comment_id 必须指向该明细名下的一条真实评论（对账列）");

        // ⑤ 目标表 NOT NULL 的列必须真的从订单侧取到了值（不是空串/占位）
        assertEquals(0L, countOf("""
                SELECT COUNT(*) FROM mall_review.review_pending_item
                 WHERE spu_title = '' OR finished_time IS NULL OR member_id IS NULL OR order_no = ''
                """), "order_no/member_id/spu_title/finished_time 必须从订单侧取到真值");

        // ⑥ 而且取到的值与订单侧的权威值逐字一致（不是"有值就行"）
        assertEquals(0L, countOf("""
                SELECT COUNT(*) FROM mall_review.review_pending_item p
                  JOIN mall_trade.oms_order_item i ON i.id = p.order_item_id
                  JOIN mall_trade.oms_order o ON o.order_no = i.order_no
                 WHERE p.order_no <> i.order_no
                    OR p.member_id <> o.member_id
                    OR p.spu_id <> i.spu_id
                    OR p.spu_title <> i.spu_title
                    OR p.finished_time <> o.finish_time
                """), "读模型的值必须与订单侧（oms_order_item / oms_order）逐字一致");

        // ⑦ 孤儿明细刻意不进读模型（无权威列值可用），而且它们也无法被重复评价
        for (Long orphanId : orphanItemIds) {
            assertEquals(0L, countOf(
                            "SELECT COUNT(*) FROM mall_review.review_pending_item WHERE order_item_id = ?", orphanId),
                    "孤儿明细 " + orphanId + " 不该被造行（目标表的 NOT NULL 列没有权威来源）");
        }
    }

    @Test
    @DisplayName("[R1] 回填脚本幂等：重复执行不新增行、也不改变任何已有行")
    void backfillIsIdempotent() throws IOException {
        String insert = extractInsert(readScript());

        // 先跑一次让数据收敛（本机已回填过时这次就是"一行都没变"）
        jdbcTemplate.update(insert);
        long rowsAfterFirstRun = countOf("SELECT COUNT(*) FROM mall_review.review_pending_item");
        String digestAfterFirstRun = pendingTableDigest();

        // 再跑两次：必须一个字节都不动
        jdbcTemplate.update(insert);
        jdbcTemplate.update(insert);

        assertEquals(rowsAfterFirstRun, countOf("SELECT COUNT(*) FROM mall_review.review_pending_item"),
                "回填脚本不幂等：重复执行新增了行");
        assertEquals(digestAfterFirstRun, pendingTableDigest(),
                "回填脚本不幂等：重复执行改变了已有行（行数/校验和/最大 update_time 三者任一变化都会在这里暴露）");

        // ⚠️ 刻意**不**用 `jdbcTemplate.update(...) == 0` 来断言幂等（本机实测踩过）：
        // Connector/J 默认 useAffectedRows=false，即客户端带 CLIENT_FOUND_ROWS 标志，
        // executeUpdate 返回的是**匹配行数**而不是**实际改变的行数**——重复执行同样返回 1885，
        // 于是"幂等"会被误判成失败。命令行 mysql 报的 "0 rows affected" 与它是两套口径。
        // 真正的不变量是"表的状态没变"，所以断言状态摘要（下面这个 helper）而不是影响行数。
    }

    @Test
    @DisplayName("[R1 背景] 源库的旧闸门对存量数据确实已失效（这是本脚本存在的理由）")
    void sourceGateIsBrokenForHistoricalRows() {
        long commentRows = countOf("SELECT COUNT(*) FROM mall_review.pms_comment");
        long staleStatusRows = countOf("""
                SELECT COUNT(*) FROM mall_review.pms_comment c
                  JOIN mall_trade.oms_order_item i ON i.id = c.order_item_id
                 WHERE i.comment_status = 0
                """);
        assertTrue(commentRows > 0, "源库应该有历史评论数据");
        assertTrue(staleStatusRows > 0,
                "实测 2500/2502 条评论对应的 oms_order_item.comment_status 仍是 0：改造前那套 CAS 防重"
                        + "对存量数据是失效的（成因是演示数据生成器绕过服务直插）。这条断言是 04 脚本的存在理由，"
                        + "同时保证用例不会在'数据被清洗过'之后还假装在验证一个真实问题");
    }

    // ==================== helpers ====================

    /**
     * 读模型整表的"状态摘要"：行数 + 逐行 CRC 校验和 + 最大 {@code update_time}。
     *
     * <p>为什么用它而不是影响行数：见 {@link #backfillIsIdempotent()} 里的注释
     * （Connector/J 默认返回"匹配行数"，幂等时也非 0）。三项一起看，覆盖"新增了行"
     * （行数变）、"改了字段"（校验和变）、"只动了时间戳"（max update_time 变）三种情况——
     * 而 ON DUPLICATE KEY UPDATE 的自赋值必须三种都不发生。
     */
    private String pendingTableDigest() {
        return stringOf("""
                SELECT CONCAT(COUNT(*), ':',
                              COALESCE(SUM(CRC32(CONCAT_WS('|',
                                  order_item_id, order_no, member_id, spu_id,
                                  COALESCE(sku_id, -1), spu_title, COALESCE(sku_image, ''), quantity,
                                  finished_time, commented, COALESCE(comment_id, -1),
                                  create_time, update_time))), 0), ':',
                              COALESCE(DATE_FORMAT(MAX(update_time), '%Y-%m-%d %H:%i:%s'), ''))
                  FROM mall_review.review_pending_item
                """);
    }

    private String readScript() throws IOException {
        Path path = Path.of(SCRIPT_RELATIVE);
        if (!Files.exists(path)) {
            // 从仓库根运行时（IDE 直接跑测试）的兜底路径，报错信息里给出两个候选，便于定位
            Path fallback = Path.of("backend", "mall-cloud", "mall-review", SCRIPT_RELATIVE);
            assertTrue(Files.exists(fallback),
                    "找不到回填脚本，试过：" + path.toAbsolutePath() + " 与 " + fallback.toAbsolutePath());
            path = fallback;
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 从脚本里取出那条 INSERT 语句（从 {@code INSERT INTO} 到其后第一个分号）。
     *
     * <p>只取 INSERT、不执行脚本末尾的自检 SELECT：用例自己断言不变量，
     * 让"脚本改坏了"与"数据不对"保持可区分。取出后剥掉 {@code --} 注释行，
     * 避免把注释里的中文原样发给服务器（语句本身没有字符串字面量，剥离是安全的）。
     */
    private String extractInsert(String script) {
        int start = script.indexOf("INSERT INTO");
        assertTrue(start >= 0, "脚本里必须有一条 INSERT 语句");
        int end = script.indexOf(';', start);
        assertTrue(end > start, "脚本里的 INSERT 语句必须以分号结束");

        StringBuilder sql = new StringBuilder();
        for (String line : script.substring(start, end).split("\r?\n")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) {
                continue;
            }
            sql.append(line).append('\n');
        }
        String result = sql.toString().strip();
        assertTrue(result.contains("ON DUPLICATE KEY UPDATE"),
                "回填语句必须带 ON DUPLICATE KEY UPDATE（幂等的前提）");
        return result;
    }
}
