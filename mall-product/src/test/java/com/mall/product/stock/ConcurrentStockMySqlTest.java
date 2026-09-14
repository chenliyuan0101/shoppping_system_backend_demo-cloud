package com.mall.product.stock;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 并发扣库存真库测试（**P6-4 的验收底线**，从单体 {@code oms.ConcurrentStockMySqlTest} 迁来并改造）。
 *
 * <h2>与原版的差别（必须说清，否则会以为"原样搬过来"）</h2>
 * <ol>
 *   <li>原版打的是 {@code POST /api/order/create}（交易域端点，需要登录/地址/订单表）——
 *       那是**单体**的用法，本服务里没有交易域。这里直接打**内部库存端点**
 *       {@code POST /internal/v1/stock/reserve}，验的是同一段代码
 *       （{@code StockCommandServiceImpl.reserve} 的条件 UPDATE），但去掉了交易域依赖
 *       ⇒ 套件可以**离线**跑（不依赖其它服务、不需要 JWT、不写订单表）；</li>
 *   <li>并发度按 P6-1 规格提到 **50 线程**（原版 6 线程）；库存 13（与原版"库存 3"同样的
 *       小库存高并发形状，且与 P6-4 探针的 S=13 对齐，便于对账）；</li>
 *   <li>新增两条独立断言（附录 I.6 第 5 条：关键结论要有第二条断言兜底）：
 *       ① 成功单数 == 流水行数；② 13 条流水的 after_stock 恰好是 {0..12} 且 before=after+1
 *       ——这条同时证明"更新后再读"（现状细节第 3 条）不是口号：
 *       若实现改成"先读后更新"，并发下 before/after 会出现重复值或对不上。</li>
 * </ol>
 *
 * <h2>为什么不用 @Transactional 回滚</h2>
 * 并发要求 UPDATE 真的落在**别的连接**上；"同一个未提交事务里自说自话"证明不了并发语义
 * （与单体原版同一理由）。代价是必须自己恢复库存/销量、删掉自己写的流水——
 * 这同时保证"新库与源库逐表行数对齐"这条验收不被测试污染。
 */
class ConcurrentStockMySqlTest extends ProductTestBase {

    /** 用真实种子 SKU（迁移过来的数据里的真实行，而不是临时造的行） */
    private static final long SKU_ID = 2001;

    private static final int STOCK = 13;
    private static final int THREADS = 50;

    @Test
    @DisplayName("[并发] 50 线程抢 13 件库存：恰好 13 单成功、37 单 409，库存不为负，流水链首尾相接")
    void concurrentReserve_noOversell() throws Exception {
        rememberStock(SKU_ID);                     // 记录基线，用例结束恢复
        Long spuId = jdbcTemplate.queryForObject(
                "SELECT spu_id FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE id = ?", Long.class, SKU_ID);
        String spuTitle = stringOf("SELECT title FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", spuId);
        jdbcTemplate.update("UPDATE " + EXPECTED_SCHEMA + ".pms_sku SET stock = ? WHERE id = ?", STOCK, SKU_ID);

        // ⚠️ orderNo 必须**在主线程**预先造好：newOrderNo() 会登记到 ownedOrderNos（ArrayList，非线程安全）
        List<String> orderNos = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            orderNos.add(newOrderNo("c"));
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict409 = new AtomicInteger();
        AtomicInteger otherCodes = new AtomicInteger();
        List<String> otherErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> unexpectedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            String orderNo = orderNos.get(i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);   // 所有线程就绪后同时发起
                    String json = "{\"orderNo\":\"" + orderNo + "\",\"lines\":[{\"skuId\":" + SKU_ID
                            + ",\"spuId\":" + spuId + ",\"quantity\":1}]}";
                    MvcResult r = mockMvc.perform(internalPost("/internal/v1/stock/reserve", json))
                            .andExpect(status().isOk())            // HTTP 恒 200，成败看 body 的 code
                            .andReturn();
                    String body = body(r);
                    int code = ((Number) JsonPath.read(body, "$.code")).intValue();
                    if (code == 0) {
                        success.incrementAndGet();
                    } else if (code == 409) {
                        conflict409.incrementAndGet();
                        String message = JsonPath.read(body, "$.message");
                        // 文案必须逐字是 "商品库存不足：{spuTitle}"（C1；另一条 "库存不足：{title}"
                        // 是 trade 侧预检的文案，见 P6-plan §6.1——两条都不能合并）
                        if (!("商品库存不足：" + spuTitle).equals(message)) {
                            unexpectedMessages.add(message);
                        }
                    } else {
                        otherCodes.incrementAndGet();
                        otherErrors.add(code + ":" + JsonPath.read(body, "$.message"));
                    }
                } catch (Exception e) {
                    otherErrors.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();

        int finalStock = stockOf(SKU_ID);

        // 本次并发产生的流水（只统计自己造的 orderNo，表里还有真实历史流水）
        List<String> afterStocks = new ArrayList<>();
        for (String orderNo : orderNos) {
            List<Integer> after = jdbcTemplate.queryForList(
                    "SELECT after_stock FROM " + EXPECTED_SCHEMA
                            + ".pms_sku_stock_log WHERE change_type = 1 AND order_no = ?",
                    Integer.class, orderNo);
            for (Integer a : after) {
                afterStocks.add(String.valueOf(a));
            }
        }
        int logRows = afterStocks.size();

        System.out.println("【P6-1 并发扣库存】线程=" + THREADS + " 初始库存=" + STOCK
                + " 成功=" + success.get() + " 409=" + conflict409.get()
                + " 其它业务码=" + otherCodes.get() + " 异常=" + otherErrors
                + " 最终库存=" + finalStock + " 本次下单流水=" + logRows + " 全部线程结束=" + finished);

        // ---- 第一条断言链：不超卖 ----
        assertTrue(finished, "线程应在 120s 内全部结束");
        assertTrue(otherErrors.isEmpty(), "不应出现死锁/超时等异常：" + otherErrors);
        assertEquals(0, otherCodes.get(), "不应出现 0/409 之外的业务码");
        assertEquals(STOCK, success.get(), "成功单数应恰好等于库存数（超卖就是这里出红）");
        assertEquals(THREADS - STOCK, conflict409.get(), "其余应全部是 409 库存不足");
        assertTrue(unexpectedMessages.isEmpty(), "409 文案必须逐字是「商品库存不足：<标题>」，实际收到：" + unexpectedMessages);
        assertEquals(0, finalStock, "库存应扣到 0，不能为负");

        // ---- 第二条（独立）断言链：流水账自洽 ----
        assertEquals(success.get(), logRows, "每笔成功扣减应恰好一条流水");
        Set<Integer> afters = new TreeSet<>(afterStocks.stream().map(Integer::valueOf).toList());
        Set<Integer> expected = new TreeSet<>();
        for (int i = 0; i < STOCK; i++) {
            expected.add(i);
        }
        assertEquals(expected, afters,
                "13 条流水的 after_stock 应恰好是 0..12 各一次（重复/缺失 = before-after 账不平）");
        int mismatchedBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + EXPECTED_SCHEMA + ".pms_sku_stock_log"
                        + " WHERE change_type = 1 AND after_stock IS NOT NULL"
                        + " AND before_stock <> after_stock + 1"
                        + " AND order_no LIKE ?", Integer.class, "P6ITc-" + suffix + "-%");
        assertEquals(0, mismatchedBefore, "扣减流水的 before_stock 必须 = after_stock + 1（delta = -1）");
    }
}
