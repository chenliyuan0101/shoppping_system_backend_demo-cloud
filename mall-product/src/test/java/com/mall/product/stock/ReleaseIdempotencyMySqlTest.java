package com.mall.product.stock;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.support.ProductTestBase;
import com.mall.product.support.constant.StockChangeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /internal/v1/stock/release} 的**幂等**真库测试（P6-5 第 2 项）。
 *
 * <h2>为什么这条必须有（不是"补个用例"，是补一个会造成库存凭空多的洞）</h2>
 * P6-4 把回补改成"trade 侧**本事务提交之后**再调 product"（{@code StockReleaseAfterCommit}），
 * 解决了"本地事务回滚后重试重复回补"，但**没有**解决"重复调用本身"：
 * <ol>
 *   <li>{@code afterCompletion} 回调里调用失败 → 对账任务重试 → 同一单再补一次；</li>
 *   <li>人工/脚本重放同一笔回补；</li>
 *   <li>超时关单与会员取消两条入口在状态 CAS 失效时先后到达。</li>
 * </ol>
 * 任何一条发生，库存都会**凭空多**——这是超卖方向、且不可逆（多出来的库存会被真实卖出去）。
 * 方案 §4.2 原文即要求"release 以 orderNo 为键，重复调用无副作用"，本套件就是这条要求的机器证据。
 *
 * <h2>幂等键为什么是 {@code (orderNo, changeType, skuId)} 三元组</h2>
 * · 只用 {@code orderNo}：同一订单的"取消回补"和"退款回补"是**两笔不同的业务**，
 *   后者会被误判成重复而**少**回补（漏库存比多库存更隐蔽：用户退款了库存却没回来）；
 * · 加上 {@code skuId}：一单多 SKU，回补是按行进行的（{@code StockLineVO} 粒度）；
 * · 于是三元组上加了唯一索引 {@code uk_order_type_sku}（见 {@code db/04-uk-order-type-sku.sql}）。
 *
 * <h2>两层防护分别由哪个用例证明</h2>
 * <ol>
 *   <li><b>判据层</b>（{@code alreadyLogged}，同事务内 {@code SELECT ... FOR UPDATE}）：
 *       {@link #release_sameKeyTwice_appliesOnlyOnce()} 证明**顺序**重复调用被收敛；</li>
 *   <li><b>索引层</b>（{@code uk_order_type_sku}）：{@link #release_sameKeyConcurrently_appliesOnlyOnce()}
 *       用 20 线程同键并发证明"判据被并发绕过时也不会静默多回补"——注意该用例**不**假设所有线程都拿到
 *       {@code code=0}：间隙锁/唯一索引的兜底方式本来就是"让后来者失败"，所以断言的是**不变式**
 *       （库存恰好 +3、流水恰好 1 行），而不是"每个线程都成功"。这两条同时成立才是 P6-5 #2 的验收。</li>
 * </ol>
 *
 * <h2>本套件**不**证明什么（别过度解读）</h2>
 * <ul>
 *   <li>不证明"跨进程的 reserve/release 原子性"——那要靠 P8 的对账，见 {@code StockReleaseAfterCommit}
 *       类注释里已上报的缺口；</li>
 *   <li>不证明 {@code orderNo} 为空时也幂等：那种情况**故意**不收敛（手工调整 {@code MANUAL_ADJUST}
 *       没有业务键，同一 SKU 允许多次人工调整），这个取舍写在 {@code alreadyLogged} 的 javadoc 里；</li>
 *   <li>不覆盖 {@code changeType} 不匹配的重复回补（例如同一订单先超时关单回补、又走退款回补）——
 *       那由订单状态 CAS 保证不会发生，不在本项范围内。</li>
 * </ul>
 *
 * <h2>为什么不用 {@code @Transactional} 回滚</h2>
 * 幂等判据依赖 {@code FOR UPDATE} 与真实提交（并发用例更要求 UPDATE 落在**别的连接**上），
 * "同一个未提交事务里自说自话"证明不了任何东西。代价是自己清理：
 * {@link #rememberStock(long)} 恢复库存、构造的 orderNo 会在 {@link ProductTestBase#cleanUp()} 里按号删流水。
 */
class ReleaseIdempotencyMySqlTest extends ProductTestBase {

    /** 真实种子 SKU（与并发套件同一个，便于对账） */
    private static final long SKU_ID = 2001;

    private static final String RELEASE = "/internal/v1/stock/release";
    private static final int QTY = 3;

    @Test
    @DisplayName("[幂等] 同一 (orderNo, changeType) 顺序调两次：库存只 +3、流水只 1 行，第二次返回成功且无副作用")
    void release_sameKeyTwice_appliesOnlyOnce() throws Exception {
        // ① 唯一索引必须在（判据之外的最后一道防线；缺了它，"判据被绕过"就没有兜底）。
        //    ⚠️ information_schema.STATISTICS 是**一列一行**（3 列索引 = 3 行），
        //    所以这里断言的是"列名与顺序"，不是"行数=1"（写成行数=1 会得到 expected 1 but was 3 的假红）。
        assertEquals("order_no,change_type,sku_id",
                jdbcTemplate.queryForObject("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)"
                        + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ?"
                        + " AND TABLE_NAME = 'pms_sku_stock_log' AND INDEX_NAME = 'uk_order_type_sku'"
                        + " AND NON_UNIQUE = 0", String.class, EXPECTED_SCHEMA),
                "db/04 的唯一索引 uk_order_type_sku(order_no,change_type,sku_id) 必须存在"
                        + "（并发下判据被绕过时的兜底）");

        rememberStock(SKU_ID);
        long spuId = spuIdOf(SKU_ID);
        trackPendingSync(spuId);          // 回补会标记待同步，清理时只摘掉自己这一个
        int before = stockOf(SKU_ID);
        String orderNo = newOrderNo("idem");
        String json = releaseJson(orderNo, StockChangeType.CANCEL_RESTORE, spuId);

        // ② 第一次：正常回补
        MvcResult first = mockMvc.perform(internalPost(RELEASE, json)).andReturn();
        assertEquals(200, first.getResponse().getStatus(), "内部端点 HTTP 状态异常: " + body(first));
        assertEquals(0, codeOf(first), "第一次回补应当成功: " + body(first));
        assertEquals(before + QTY, stockOf(SKU_ID), "第一次回补后库存应当 +" + QTY);
        assertEquals(1L, logRows(orderNo, StockChangeType.CANCEL_RESTORE), "第一次应当恰好写 1 行流水");

        // ③ 第二次：同一请求体原样重放 ⇒ 必须被收敛，且**不是**报错（对账重试不该因为"已经补过"而失败）
        MvcResult second = mockMvc.perform(internalPost(RELEASE, json)).andReturn();
        assertEquals(200, second.getResponse().getStatus(), "HTTP 必须恒 200: " + body(second));
        assertEquals(0, codeOf(second), "重复回补应当是「成功但无副作用」，而不是报错: " + body(second));
        assertEquals(before + QTY, stockOf(SKU_ID),
                "第二次**必须**不再加库存——再 +" + QTY + " 就是库存凭空多（超卖方向、不可逆）");
        assertEquals(1L, logRows(orderNo, StockChangeType.CANCEL_RESTORE),
                "第二次不应新增流水：幂等键 (orderNo, changeType, skuId) 唯一");

        // ④ 剩下那 1 行必须是**第一次**写的（证明被跳过的是第二次，而不是第一次没落库）
        assertEquals(before + QTY, (int) intOf("SELECT after_stock FROM " + EXPECTED_SCHEMA
                        + ".pms_sku_stock_log WHERE order_no = ? AND change_type = ? AND sku_id = ?",
                orderNo, StockChangeType.CANCEL_RESTORE, SKU_ID),
                "流水里的 after_stock 应当停在第 1 次回补后的值");
    }

    @Test
    @DisplayName("[幂等] 换一个 changeType 不算重复：同一 orderNo 的取消回补 + 超时回补各自生效（不做过度收敛）")
    void release_differentChangeType_isNotSuppressed() throws Exception {
        rememberStock(SKU_ID);
        long spuId = spuIdOf(SKU_ID);
        trackPendingSync(spuId);
        int before = stockOf(SKU_ID);
        String orderNo = newOrderNo("two");

        assertEquals(0, codeOf(mockMvc.perform(
                internalPost(RELEASE, releaseJson(orderNo, StockChangeType.CANCEL_RESTORE, spuId))).andReturn()),
                "取消回补应当成功");
        assertEquals(0, codeOf(mockMvc.perform(
                internalPost(RELEASE, releaseJson(orderNo, StockChangeType.TIMEOUT_RESTORE, spuId))).andReturn()),
                "换 changeType 的第二次回补应当成功（幂等键含 changeType）");

        assertEquals(before + QTY * 2, stockOf(SKU_ID),
                "两种 changeType 是两笔不同业务 ⇒ 都应当回补（过度收敛会**漏**库存，比多库存更隐蔽）");
        assertEquals(1L, logRows(orderNo, StockChangeType.CANCEL_RESTORE), "取消回补 1 行");
        assertEquals(1L, logRows(orderNo, StockChangeType.TIMEOUT_RESTORE), "超时回补 1 行");
    }

    @Test
    @DisplayName("[幂等/并发] 20 线程同键并发 release：库存恰好 +3、流水恰好 1 行（间隙锁 + 唯一索引兜底）")
    void release_sameKeyConcurrently_appliesOnlyOnce() throws Exception {
        rememberStock(SKU_ID);
        long spuId = spuIdOf(SKU_ID);
        trackPendingSync(spuId);
        int before = stockOf(SKU_ID);

        // ⚠️ orderNo 必须在主线程先造好：newOrderNo() 会写 ownedOrderNos（非线程安全）
        String orderNo = newOrderNo("race");
        String json = releaseJson(orderNo, StockChangeType.REFUND_RESTORE, spuId);

        int threads = 20;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger httpOk = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    MvcResult r = mockMvc.perform(internalPost(RELEASE, json)).andReturn();
                    if (r.getResponse().getStatus() == 200) {
                        httpOk.incrementAndGet();
                    }
                    String b = body(r);
                    int code = ((Number) JsonPath.read(b, "$.code")).intValue();
                    if (code == 0) {
                        ok.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                        failures.add(r.getResponse().getStatus() + "/" + code + ":" + JsonPath.read(b, "$.message"));
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    failures.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished, "并发回补在 120s 内未跑完（ok=" + ok.get() + " failed=" + failed.get() + "）");

        // 不变式（与"每个线程都成功"无关，见类注释）：库存与流水都被"只生效一次"钉死
        String tally = "ok=" + ok.get() + " failed=" + failed.get() + " httpOk=" + httpOk.get()
                + " 失败详情=" + failures;
        assertEquals(before + QTY, stockOf(SKU_ID),
                "20 线程同键并发回补后库存必须恰好 +" + QTY + "（多一件就是超卖方向）｜" + tally);
        assertEquals(1L, logRows(orderNo, StockChangeType.REFUND_RESTORE),
                "同键并发只允许留下 1 行流水｜" + tally);
        assertTrue(ok.get() >= 1, "至少要有一个线程真的回补成功（否则是'全都失败了'而不是'只生效一次'）｜" + tally);
        assertEquals(threads, httpOk.get(),
                "内部端点的**业务失败也必须是 HTTP 200**（C1：成败只看 body.code）｜" + tally);
    }

    // ==================================================================
    // 局部辅助
    // ==================================================================

    private String releaseJson(String orderNo, int changeType, long spuId) {
        return "{\"orderNo\":\"" + orderNo + "\",\"changeType\":" + changeType
                + ",\"lines\":[{\"skuId\":" + SKU_ID + ",\"spuId\":" + spuId + ",\"quantity\":" + QTY + "}]}";
    }

    private long spuIdOf(long skuId) {
        Long spuId = jdbcTemplate.queryForObject(
                "SELECT spu_id FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE id = ?", Long.class, skuId);
        return spuId == null ? 0L : spuId;
    }

    private long logRows(String orderNo, int changeType) {
        return countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_sku_stock_log WHERE order_no = ? AND change_type = ? AND sku_id = ?",
                orderNo, changeType, SKU_ID);
    }

    private int codeOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(body(result), "$.code")).intValue();
    }
}
