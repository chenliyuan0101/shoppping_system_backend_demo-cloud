package com.mall.admin.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三个"下游服务"的环回桩（trade / product / user-center），给 P7 后半的看板与会员用例共用。
 *
 * <h2>为什么用环回 HTTP 桩，而不是 {@code @MockitoBean}</h2>
 * 被测的正是"**真实的 HTTP 客户端**在不可达 / 超时 / 非 0 业务码时怎么表现"，以及
 * "**并发提交的三个请求到底有没有重叠**"：
 * <ul>
 *   <li>换掉客户端 ⇒ 超时、连接失败这些**传输层**行为根本没被执行
 *       （测的是"我 mock 出来的行为"，不是真实行为——P5/P6 都明确禁过这类假绿）；</li>
 *   <li>并发重叠只能由"服务端同时看到几个请求"来证明：桩在进入处理函数时**先把在途计数 +1**、
 *       应答前 -1，峰值并发就是"客户端真的同时发了几条"的硬证据
 *       （断言客户端耗时只能说明"没串行"，说明不了"真的并发")。</li>
 * </ul>
 * ⚠️ 每个桩必须用**多线程** executor：{@code HttpServer} 默认是单线程调度，
 * 三个并发请求会被桩自己排成队，峰值并发永远是 1 ⇒ 用例会以"实现串行"为由失败（假红）。
 *
 * <h2>三个模式（与规格 §3 的"三种不可用"对应）</h2>
 * <ul>
 *   <li>{@link Mode#OK}：正常应答（{@code slowMs} &gt; 0 时先睡再答，用于并发重叠测量）；</li>
 *   <li>{@link Mode#NONZERO_CODE}：应答 HTTP 200 + {@code code=500}（**非 0 下游业务码**）；</li>
 *   <li>{@link Mode#SLOW}：睡 {@code slowMs} 后再答 ⇒ 触发客户端**读超时**。</li>
 * </ul>
 * 第四种"服务根本不在"（连接被拒）**不在这里**：它要求地址指向一个没人监听的端口，
 * 由三个 {@code AdminDashboard*DownTest} 通过 {@code ${...:http://127.0.0.1:9}} 的写法覆盖
 * （与 mall-product 的 {@code ProductTestBase} 同一手法）。
 *
 * <h2>数据是"可独立算出来"的</h2>
 * 每个返回值都用常量或从请求里解析出的 id 现算（如批量补数按 id 定值），
 * 因此断言不依赖"桩记住上一次给了什么"，也不会出现"桩和被测代码一起错还相互印证"。
 */
public final class DownstreamStubs {

    private DownstreamStubs() {
    }

    /** 桩的行为模式 */
    public enum Mode { OK, NONZERO_CODE, SLOW }

    // ==================== 契约常量（断言直接引用，避免测试里散落魔法数字） ====================

    public static final long TRADE_TODAY_ORDER_COUNT = 11L;
    public static final long TRADE_TODAY_SALES_AMOUNT = 22L;
    public static final long TRADE_WAIT_SHIP_COUNT = 33L;
    public static final long TRADE_REFUND_PENDING_COUNT = 44L;
    public static final long PRODUCT_ENABLED_COUNT = 77L;
    public static final long USER_MEMBER_COUNT = 66L;
    public static final long MEMBER_PAGE_TOTAL = 123L;
    /** 销量榜第一名的 spuId（也用于"销售额榜补标题"的断言） */
    public static final long TOP_SPU_ID = 9101L;
    /** 销售额榜第二名的 spuId */
    public static final long TOP_SPU_ID_2 = 9102L;
    public static final long TOP_SPU1_SALES = 9L;
    public static final long TOP_SPU1_AMOUNT = 5550L;
    /** 详情补数：任意会员的订单数（固定值，便于断言） */
    public static final long DETAIL_ORDER_COUNT = 7L;
    public static final long DETAIL_PAID_AMOUNT = 7_000L;
    /** 桩认为"不存在"的会员 id（快照返回 data=null；改状态返回 404 会员不存在） */
    public static final long MISSING_MEMBER_ID = 9_999_999L;
    /** 桩认为非法的状态值（下游返回 400「状态值仅支持 0禁用 1正常」，验证文案原样透传） */
    public static final int INVALID_STATUS = 5;
    /** 下游失败时 {@code code} 非 0 的文案（用于断言"降级不看文案、只看 code"） */
    public static final String NONZERO_MESSAGE = "桩服务模拟下游业务失败";

    // ==================== 并发观测（三个桩共享，用于"并行重叠"证明） ====================

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger PEAK_IN_FLIGHT = new AtomicInteger();
    /**
     * "复位世代"：{@link #resetConcurrency()} 时 +1。
     *
     * <p>⚠️ 为什么需要它（不是过度设计）：{@code Mode.SLOW} 的用例里，桩线程会在客户端
     * <b>读超时之后</b>还在睡（那正是"下游停半路"的现场）。如果它醒来时还去把在途计数 -1，
     * 就会把**下一个用例**的计数拉成负数，下一个用例的"峰值并发 == 3"就可能读成 2（假红）
     * 或者把上一个用例的残留在途算进来（假绿）。世代号让"跨复位的 -1"变成空操作。
     */
    private static final AtomicInteger EPOCH = new AtomicInteger();

    public static int peakInFlight() {
        return PEAK_IN_FLIGHT.get();
    }

    public static void resetConcurrency() {
        EPOCH.incrementAndGet();
        IN_FLIGHT.set(0);
        PEAK_IN_FLIGHT.set(0);
    }

    // ==================== 三个桩 ====================

    public static final Stub TRADE = create("trade", DownstreamStubs::routeTrade);
    public static final Stub PRODUCT = create("product", DownstreamStubs::routeProduct);
    public static final Stub USER = create("user", DownstreamStubs::routeUser);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            TRADE.stop();
            PRODUCT.stop();
            USER.stop();
        }, "downstream-stubs-shutdown"));
    }

    /** 全部桩复位（在每个用例开始处调用：模式、慢速、计数、在途峰值一起清） */
    public static void resetAll() {
        TRADE.reset();
        PRODUCT.reset();
        USER.reset();
        resetConcurrency();
    }

    // ==================================================================
    // 桩本体
    // ==================================================================

    /** 一个下游桩：记录收到的每个请求（路径/请求体/内部令牌），并可按模式给出不同的"不可用"表现 */
    public static final class Stub {

        public final String name;
        private final HttpServer server;
        private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
        private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        private final List<String> tokens = Collections.synchronizedList(new ArrayList<>());

        private volatile Mode mode = Mode.OK;
        private volatile long slowMs = 0L;
        /** 商品域专用：把这些 spuId 从批量快照里**故意漏掉**（模拟"商品已被删除"，与"服务不可用"不同） */
        private volatile Long omitSpuId = null;

        private Stub(String name, HttpServer server) {
            this.name = name;
            this.server = server;
        }

        public String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        public int port() {
            return server.getAddress().getPort();
        }

        public Stub mode(Mode newMode) {
            this.mode = newMode;
            return this;
        }

        /** 正常应答前先睡这么久（并发重叠测量用；配合短的读超时就是"超时"场景） */
        public Stub slow(long millis) {
            this.slowMs = millis;
            return this;
        }

        public Stub omitSpu(Long spuId) {
            this.omitSpuId = spuId;
            return this;
        }

        public void reset() {
            mode = Mode.OK;
            slowMs = 0L;
            omitSpuId = null;
            paths.clear();
            bodies.clear();
            tokens.clear();
        }

        /** 收到过的请求路径（含 query） */
        public List<String> paths() {
            return new ArrayList<>(paths);
        }

        public List<String> bodies() {
            return new ArrayList<>(bodies);
        }

        public int calls() {
            return paths.size();
        }

        /** 命中某段路径的调用次数（例如 {@code /member/page}） */
        public int callsTo(String pathPart) {
            int n = 0;
            synchronized (paths) {
                for (String path : paths) {
                    if (path.contains(pathPart)) {
                        n++;
                    }
                }
            }
            return n;
        }

        /** 最后一次收到的请求体（断言"发出去的契约"用） */
        public String lastBody() {
            synchronized (bodies) {
                return bodies.isEmpty() ? null : bodies.get(bodies.size() - 1);
            }
        }

        public List<String> tokens() {
            return new ArrayList<>(tokens);
        }

        private void stop() {
            server.stop(0);
        }
    }

    private interface Router {
        String route(Stub stub, HttpExchange exchange, String path, String body) throws IOException;
    }

    private static Stub create(String name, Router router) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Stub stub = new Stub(name, server);
            server.createContext("/", exchange -> handle(stub, router, exchange));
            // ⚠️ 必须多线程（见类注释）：单线程调度会把"并发"变成"排队"，用例会假红
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 " + name + " 环回桩", e);
        }
    }

    private static void handle(Stub stub, Router router, HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        // 在途计数：**进入处理函数就 +1、应答前 -1** ⇒ 峰值就是"客户端同时发了几条"
        int epoch = EPOCH.get();
        int now = IN_FLIGHT.incrementAndGet();
        PEAK_IN_FLIGHT.accumulateAndGet(now, Math::max);
        stub.paths.add(query == null ? path : path + "?" + query);
        stub.bodies.add(body);
        stub.tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        try {
            if (stub.slowMs > 0) {
                sleep(stub.slowMs);
            }
            Mode mode = stub.mode;
            String payload;
            if (mode == Mode.NONZERO_CODE) {
                payload = "{\"code\":500,\"message\":\"" + NONZERO_MESSAGE + "\",\"data\":null}";
            } else {
                payload = router.route(stub, exchange, path, body);
            }
            respond(exchange, 200, payload);
        } catch (Exception e) {
            // 桩自身出问题要能看见，不要伪装成"下游失败"
            safeRespond(exchange, 500, "{\"code\":500,\"message\":\"桩服务异常: " + e + "\",\"data\":null}");
        } finally {
            // 只在"同一个复位世代"内 -1（见 EPOCH 的注释：SLOW 用例的沉睡线程不该动下一轮的计数）
            if (EPOCH.get() == epoch) {
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    // ==================================================================
    // 交易域（今天 = 单体 mall-legacy）的应答
    // ==================================================================

    private static String routeTrade(Stub stub, HttpExchange exchange, String path, String body) {
        if (path.endsWith("/stat/summary")) {
            return "{\"code\":0,\"message\":\"ok\",\"data\":{\"todayOrderCount\":" + TRADE_TODAY_ORDER_COUNT
                    + ",\"todaySalesAmount\":" + TRADE_TODAY_SALES_AMOUNT
                    + ",\"waitShipCount\":" + TRADE_WAIT_SHIP_COUNT
                    + ",\"refundPendingCount\":" + TRADE_REFUND_PENDING_COUNT + "}}";
        }
        if (path.endsWith("/stat/trend")) {
            // 按 days 现造 points：**个数 == days** ⇒ 断言它就能证明 days 被原样透传
            int days = intParam(exchange.getRequestURI().getQuery(), "days", 7);
            StringBuilder sb = new StringBuilder("{\"code\":0,\"message\":\"ok\",\"data\":[");
            for (int i = 0; i < days; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"date\":\"2026-09-").append(String.format("%02d", i + 1))
                        .append("\",\"orderCount\":").append(i + 1)
                        .append(",\"salesAmount\":").append((i + 1) * 100L).append('}');
            }
            return sb.append("]}").toString();
        }
        if (path.endsWith("/stat/top-amount")) {
            return "{\"code\":0,\"message\":\"ok\",\"data\":["
                    + "{\"spuId\":" + TOP_SPU_ID + ",\"amount\":" + TOP_SPU1_AMOUNT + "},"
                    + "{\"spuId\":" + TOP_SPU_ID_2 + ",\"amount\":4440}]}";
        }
        if (path.endsWith("/stat/member-order-brief/batch")) {
            StringBuilder sb = new StringBuilder("{\"code\":0,\"message\":\"ok\",\"data\":{");
            List<Long> ids = longArray(body, "memberIds");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                long id = ids.get(i);
                sb.append('"').append(id).append("\":{\"orderCount\":").append(orderCountOf(id))
                        .append(",\"paidAmount\":").append(paidOf(id)).append('}');
            }
            return sb.append("}}").toString();
        }
        if (path.endsWith("/stat/member-order-brief")) {
            return "{\"code\":0,\"message\":\"ok\",\"data\":{\"orderCount\":" + DETAIL_ORDER_COUNT
                    + ",\"paidAmount\":" + DETAIL_PAID_AMOUNT + "}}";
        }
        return notFound(path);
    }

    /** 批量补数的定值口径：订单数 = 1 + id%5，实付 = id*100（可独立算出，不依赖调用顺序） */
    public static long orderCountOf(long memberId) {
        return 1L + (memberId % 5L);
    }

    public static long paidOf(long memberId) {
        return memberId * 100L;
    }

    // ==================================================================
    // 商品域的应答
    // ==================================================================

    private static String routeProduct(Stub stub, HttpExchange exchange, String path, String body) {
        if (path.endsWith("/product/stat/enabled-count")) {
            return "{\"code\":0,\"message\":\"ok\",\"data\":" + PRODUCT_ENABLED_COUNT + "}";
        }
        if (path.endsWith("/product/stat/top-sales")) {
            return "{\"code\":0,\"message\":\"ok\",\"data\":["
                    + spuJson(TOP_SPU_ID, "桩商品A", TOP_SPU1_SALES) + ","
                    + spuJson(TOP_SPU_ID_2, "桩商品B", 4L) + "]}";
        }
        if (path.endsWith("/product/spu/batch")) {
            StringBuilder sb = new StringBuilder("{\"code\":0,\"message\":\"ok\",\"data\":[");
            List<Long> ids = longArray(body, "ids");
            boolean first = true;
            for (Long id : ids) {
                if (stub.omitSpuId != null && stub.omitSpuId.equals(id)) {
                    continue;   // 模拟"商品已被删除"：**调用成功**但没有这条
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(spuJson(id, "桩商品" + id, 1L));
            }
            return sb.append("]}").toString();
        }
        return notFound(path);
    }

    private static String spuJson(long id, String title, long sales) {
        return "{\"id\":" + id + ",\"title\":\"" + title + "\",\"subtitle\":null,"
                + "\"mainImage\":\"http://img/" + id + ".jpg\",\"sales\":" + sales + ",\"status\":1}";
    }

    // ==================================================================
    // 会员域的应答
    // ==================================================================

    private static String routeUser(Stub stub, HttpExchange exchange, String path, String body) {
        if (path.endsWith("/user/member/count")) {
            return "{\"code\":0,\"message\":\"ok\",\"data\":" + USER_MEMBER_COUNT + "}";
        }
        if (path.endsWith("/user/member/page")) {
            long pageNum = longField(body, "pageNum", 1L);
            long pageSize = longField(body, "pageSize", 10L);
            StringBuilder sb = new StringBuilder("{\"code\":0,\"message\":\"ok\",\"data\":{\"total\":"
                    + MEMBER_PAGE_TOTAL + ",\"pageNum\":" + pageNum + ",\"pageSize\":" + pageSize + ",\"list\":[");
            for (int i = 0; i < pageSize; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(memberJson(memberIdAt(pageNum, i)));
            }
            return sb.append("]}}").toString();
        }
        if (path.endsWith("/snapshot")) {
            long id = idInPath(path, "/user/member/", "/snapshot");
            if (id == MISSING_MEMBER_ID) {
                return "{\"code\":0,\"message\":\"ok\",\"data\":null}";
            }
            return "{\"code\":0,\"message\":\"ok\",\"data\":" + memberJson(id) + "}";
        }
        if (path.endsWith("/status")) {
            long id = idInPath(path, "/user/member/", "/status");
            if (id == MISSING_MEMBER_ID) {
                return "{\"code\":404,\"message\":\"会员不存在\",\"data\":null}";
            }
            long status = longField(body, "status", -1L);
            if (status != 0L && status != 1L) {
                return "{\"code\":400,\"message\":\"状态值仅支持 0禁用 1正常\",\"data\":null}";
            }
            return "{\"code\":0,\"message\":\"ok\",\"data\":null}";
        }
        return notFound(path);
    }

    /** 第 N 页第 i 条的会员 id（**页与页之间不重叠**，便于断言"补的是这一页的 id"） */
    public static long memberIdAt(long pageNum, int indexInPage) {
        return pageNum * 100L + indexInPage;
    }

    public static String memberJson(long id) {
        return "{\"id\":" + id + ",\"username\":\"member" + id + "\",\"nickname\":\"会员" + id
                + "\",\"phone\":\"1380000" + String.format("%04d", id % 10000)
                + "\",\"avatar\":\"http://img/member" + id + ".jpg\",\"status\":1,"
                + "\"createTime\":\"2026-01-02T03:04:05\"}";
    }

    // ==================================================================
    // 解析/构造小工具
    // ==================================================================

    private static String notFound(String path) {
        return "{\"code\":404,\"message\":\"桩服务未实现该路径: " + path + "\",\"data\":null}";
    }

    private static void respond(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void safeRespond(HttpExchange exchange, int status, String payload) {
        try {
            respond(exchange, status, payload);
        } catch (IOException ignored) {
            // 客户端已经超时断开：这不是桩的错，别掩盖成"下游失败"
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int intParam(String query, String name, int fallback) {
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try {
                    return Integer.parseInt(kv[1]);
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static long longField(String json, String field, long fallback) {
        if (json == null) {
            return fallback;
        }
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : fallback;
    }

    /** 取 {@code "field":[1,2,3]} 里的数字列表（桩只需要能解析自己期待的形状） */
    private static List<Long> longArray(String json, String field) {
        List<Long> ids = new ArrayList<>();
        if (json == null) {
            return ids;
        }
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
        if (!matcher.find()) {
            return ids;
        }
        for (String part : matcher.group(1).split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(Long.parseLong(trimmed));
            }
        }
        return ids;
    }

    private static long idInPath(String path, String prefix, String suffix) {
        int start = path.indexOf(prefix);
        int end = path.lastIndexOf(suffix);
        if (start < 0 || end < 0 || end <= start) {
            return -1L;
        }
        return Long.parseLong(path.substring(start + prefix.length(), end));
    }
}
