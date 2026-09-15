package com.mall.trade.oms.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mall.trade.common.MqTopology;
import com.mall.trade.oms.service.StatService;
import com.mall.trade.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.mall.common.support.MemberId;

/**
 * 领域事件消费链路测试（单体侧）：支付成功 / 退款到账 → <b>订单当日统计重算</b>。
 *
 * <p><b>P3-5 的改造：本套件由 {@code ums.NotificationStatMqMySqlTest} 改成"只剩统计"</b>。
 * 原来的消费者一次消费干两件事（① 写 {@code ums_notification}；② 重算 {@code oms_order_daily_stat}），
 * 现在通知那一半随属主（会员域）搬到 {@code mall-user-center} 的独立队列
 * （{@code mall.user.notification}，绑同一条事件交换机 {@code mall.oms.event}），
 * 单体只保留统计——于是：
 * <ul>
 *   <li>本套件断言的全是 <b>oms 自己的表</b>（{@code oms_order_daily_stat}：从源表重算的派生数据，
 *       "重算式 upsert"天然幂等，重复投递不改变结果）；</li>
 *   <li>"支付/发货/退款各写一条站内消息、重复投递只落一条"这些断言搬到了 user-center 的
 *       {@code NotificationEventMqMySqlTest}（真 MQ，断言落 {@code mall_user.ums_notification}）；
 *       本套件反过来钉一条<b>否定断言</b>：事件被消费完之后，{@code mall.ums_notification} 不该多出任何行
 *       ——这是"单体确实不再写通知、写入方只剩一个"的可执行证据。</li>
 * </ul>
 *
 * <p>与其它 MQ 套件同样的约定：测试期默认关闭 MQ（见 {@code src/test/resources/application.properties}），
 * 本套件用 properties 单独开启；事件重试压到 200ms/1 次；订单超时兜底扫描保持 1 小时（避免干扰）。
 *
 * <p>断言都是**相对基线**（统计表是"从源表重算"的派生数据，今天已有批量演示订单，不能写绝对值）。
 */
@SpringBootTest(properties = {
        "mall.mq.enabled=true",
        "mall.mq.event-retry-delay-ms=200",
        "mall.mq.event-max-retry=1"
})
@AutoConfigureMockMvc
class OrderEventStatMqMySqlTest extends MySqlTestBase {

    private static final long SKU_ID = 2001L;

    @Autowired
    private StatService statService;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void requireRabbitMq() {
        assumeTrue(rabbitUp(), "RabbitMQ 未启动(127.0.0.1:5672 不通)，跳过");
        purgeEventQueues();
    }

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
        // 统计是重算出来的：用例产生的订单被删除后再重算一次，今天的数字就回到基线
        statService.refreshToday();
        purgeEventQueues();
    }

    @Test
    @DisplayName("[事件] 支付成功 → 当日支付统计 +1；单体不再写站内消息")
    void paid_refreshesStatAndWritesNoNotification() throws Exception {
        String token = registerAs("ntfpay_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(SKU_ID);

        long paidBefore = sourcePaidCount();
        long amountBefore = sourcePaidAmount();

        String orderNo = buyNow(token, addressId, SKU_ID, 1);
        long payAmount = orderAmount(orderNo);
        pay(auth, orderNo);

        // 当日统计：重算后支付笔数与金额都 +1 笔（统计是**消费者**算出来的 → 这一步同时证明事件被消费了）
        boolean statOk = waitUntil(15_000, () -> todayStat("paid_count") == paidBefore + 1);
        assertThat(statOk).as("当日支付笔数应从 %s 变为 %s", paidBefore, paidBefore + 1).isTrue();
        assertThat(todayStat("paid_amount")).isEqualTo(amountBefore + payAmount);

        // [P3-5 否定断言] 等事件队列排空（消息消费完），mall.ums_notification 依然不能有新行：
        // 通知的写入方是 mall-user-center（它写的是自己的 mall_user.ums_notification，见其 MQ 套件）。
        assertThat(waitUntil(10_000, () -> eventQueueDepth() == 0))
                .as("事件队列应被消费干净（%s）", MqTopology.EVENT_QUEUE).isTrue();
        assertThat(mallNotificationRows(orderNo))
                .as("单体 P3-5 起不应再写 mall.ums_notification（通知写入方只剩 user-center）")
                .isZero();
        assertThat(notificationTableAbsentInOwnSchema())
                .as("P8-2 起本服务连自己的库里都没有通知表（%s）", "mall_trade")
                .isTrue();
    }

    @Test
    @DisplayName("[事件] 退款到账 → 当日退款统计 +1")
    void refundSettled_refreshesStat() throws Exception {
        String token = registerAs("ntfref_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(SKU_ID);

        String orderNo = buyNow(token, addressId, SKU_ID, 1);
        pay(auth, orderNo);

        long refundCountBefore = sourceRefundCount();
        long refundAmountBefore = sourceRefundAmount();
        long payAmount = orderAmount(orderNo);

        mockMvc.perform(post("/api/refund/apply").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"refundType\":1,\"reason\":\"统计链路测试\"}"))
                .andExpect(jsonPath("$.code").value(0));

        String paged = mockMvc.perform(get("/api/admin/refund/page").headers(adminHeaders())
                        .param("pageSize", "1"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        long refundId = ((Number) JsonPath.read(paged, "$.data.list[0].id")).longValue();
        mockMvc.perform(post("/api/admin/refund/" + refundId + "/approve").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));

        boolean statOk = waitUntil(15_000, () -> todayStat("refund_count") == refundCountBefore + 1);
        assertThat(statOk).as("当日退款笔数应从 %s 变为 %s", refundCountBefore, refundCountBefore + 1).isTrue();
        assertThat(todayStat("refund_amount")).isEqualTo(refundAmountBefore + payAmount);

        // 同样不允许出现站内消息残留（退款事件也不写通知）
        assertThat(mallNotificationRows(orderNo))
                .as("退款事件不应在单体写 mall.ums_notification")
                .isZero();
        assertThat(notificationTableAbsentInOwnSchema())
                .as("P8-2 起本服务连自己的库里都没有通知表（%s）", "mall_trade")
                .isTrue();
    }

    @Test
    @DisplayName("[统计] 后台统计接口：概览 / 按日列表 / 手动重算(与源表口径一致)")
    void adminStatApis() throws Exception {

        mockMvc.perform(get("/api/admin/stat/overview").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.todayDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.data.weekDays").value(7));

        mockMvc.perform(get("/api/admin/stat/daily").headers(adminHeaders()).param("days", "3"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/api/admin/stat/refresh").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.date").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.data.refreshed").value(true));

        // [契约] overview.today 与 daily 元素都是"日统计快照"，字段名必须与 oms_order_daily_stat 一一对应。
        // P0 边界冻结把这两处的 Java 类型从持久化实体换成契约快照 OrderDailyStatVO（去实体化），
        // 这组断言就是"对外 JSON 契约未变"的证据（见《微服务改造方案.md》附录 D 批次 3/4）。
        String overviewBody = mockMvc.perform(get("/api/admin/stat/overview").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonKeysAt(overviewBody, "/data/today"))
                .as("overview.today 的 JSON 字段")
                .containsExactlyInAnyOrder("id", "statDate", "orderCount", "paidCount",
                        "paidAmount", "refundCount", "refundAmount", "updateTime");

        String dailyBody = mockMvc.perform(get("/api/admin/stat/daily")
                        .headers(adminHeaders()).param("days", "3"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonKeysAt(dailyBody, "/data/0"))
                .as("daily[0] 的 JSON 字段")
                .containsExactlyInAnyOrder("id", "statDate", "orderCount", "paidCount",
                        "paidAmount", "refundCount", "refundAmount", "updateTime");

        // 重算后统计值必须与"从源表算出来的应有值"一致（这是派生数据的核心契约）
        assertThat(todayStat("order_count")).isEqualTo(sourceOrderCount());
        assertThat(todayStat("paid_count")).isEqualTo(sourcePaidCount());
        assertThat(todayStat("paid_amount")).isEqualTo(sourcePaidAmount());
        assertThat(todayStat("refund_count")).isEqualTo(sourceRefundCount());
    }

    // ==================== helpers ====================

    /** 取指定 JSON 指针（如 {@code /data/today}）下的字段名集合：用于把响应字段名钉死，防契约漂移 */
    private static Set<String> jsonKeysAt(String body, String pointer) throws Exception {
        JsonNode node = new ObjectMapper().readTree(body).at(pointer);
        assertThat(node.isMissingNode() || node.isNull())
                .as("JSON 指针 %s 未命中（响应为 %s）", pointer, body)
                .isFalse();
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private long orderAmount(String orderNo) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT pay_amount FROM oms_order WHERE order_no = ?", Long.class, orderNo);
        return v == null ? 0L : v.longValue();
    }

    /**
     * 旧单体库里那张"已无人写入"的通知表：按业务单号数行（P3-5 的否定断言用它）。
     *
     * <p>历史沿革（三次口径，都留在这里，别删）：
     * <ul>
     *   <li>P3-5 起：断言写的是**不带 schema 名**的 `ums_notification` —— 当时连接默认库是 `mall`，能用；</li>
     *   <li>P8-2 起：连接默认库变成 `mall_trade` ⇒ 不带前缀会 `BadSqlGrammar`，改成显式 `mall.ums_notification`；</li>
     *   <li>**P8-5 起：`mall.ums_notification` 这张表被删掉了**（`mall` 已成空库）⇒ 再查它就永远是
     *       `BadSqlGrammar`（表不存在）。**正确处理不是删掉这条断言，而是把"表不存在"本身当成更强的判据**：
     *       表都没了，"单体还写不写它"这个问题自然是否定的。所以这里先探测表是否存在，不存在直接返回 0。</li>
     * </ul>
     * 与之配合的是 {@link #notificationTableAbsentInOwnSchema()}（本服务自己的库里也没有通知表）。
     */
    private long mallNotificationRows(String bizNo) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'ums_notification'",
                Integer.class);
        if (exists == null || exists == 0) {
            return 0L;   // P8-5 之后就是这条路：老库那张表整体不存在
        }
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mall.ums_notification WHERE member_id = ? AND biz_no = ?",
                Long.class, memberId, bizNo);
        return n == null ? 0L : n;
    }

    /**
     * 更强的一条（P8-2 新增）：**本服务的库里根本没有通知表** ——
     * 这比"那张表没有新增行"更进一步：连写的可能性都不存在。P8-5 删掉 `mall.ums_notification` 之后，
     * 上面那条按行数的断言会退化成"表不存在"，届时由本方法承担判据。
     */
    private boolean notificationTableAbsentInOwnSchema() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ums_notification'",
                Integer.class);
        return n != null && n == 0;
    }

    /** 事件工作队列的深度；队列不存在（还没声明）返回 -1 */
    private long eventQueueDepth() {
        try {
            var info = amqpAdmin.getQueueInfo(MqTopology.EVENT_QUEUE);
            return info == null ? -1 : info.getMessageCount();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 今天的统计值；行还不存在时按 0（统计表是懒生成的：第一次有事件才会建行） */
    private long todayStat(String column) {
        Number v = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(" + column + "),0) FROM oms_order_daily_stat WHERE stat_date = ?",
                Number.class, LocalDate.now());
        return v == null ? 0L : v.longValue();
    }

    /**
     * 统计的"应有值"直接从源表算（与 StatService 的重算口径一致）。
     *
     * <p>为什么不用 {@link #todayStat} 做基线：统计表是<b>懒生成</b>的——今天还没有任何事件时压根没有行，
     * 而今天的库里已有批量演示订单（几千笔），一旦第一笔事件触发重算，行里的数字会"从 0 跳到几千"，
     * 用 0 做基线会误判。拿源表算基线才是真正的相对断言。
     */
    private long sourceOrderCount() {
        Number v = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oms_order WHERE deleted = 0 AND DATE(create_time) = ?",
                Number.class, LocalDate.now());
        return v == null ? 0L : v.longValue();
    }

    private long sourcePaidCount() {
        Number v = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oms_order WHERE deleted = 0 AND pay_status >= 1 AND DATE(pay_time) = ?",
                Number.class, LocalDate.now());
        return v == null ? 0L : v.longValue();
    }

    private long sourcePaidAmount() {
        Number v = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(pay_amount),0) FROM oms_order WHERE deleted = 0 AND pay_status >= 1 AND DATE(pay_time) = ?",
                Number.class, LocalDate.now());
        return v == null ? 0L : v.longValue();
    }

    private long sourceRefundCount() {
        Number v = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oms_refund WHERE status = 2 AND DATE(finish_time) = ?",
                Number.class, LocalDate.now());
        return v == null ? 0L : v.longValue();
    }

    private long sourceRefundAmount() {
        Number v = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount),0) FROM oms_refund WHERE status = 2 AND DATE(finish_time) = ?",
                Number.class, LocalDate.now());
        return v == null ? 0L : v.longValue();
    }

    private void purgeEventQueues() {
        for (String q : new String[]{MqTopology.EVENT_QUEUE, MqTopology.EVENT_RETRY_QUEUE, MqTopology.EVENT_DLQ}) {
            try {
                amqpAdmin.purgeQueue(q);
            } catch (Exception ignored) {
                // 队列还没声明时忽略
            }
        }
    }

    private boolean rabbitUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5672), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitUntil(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
