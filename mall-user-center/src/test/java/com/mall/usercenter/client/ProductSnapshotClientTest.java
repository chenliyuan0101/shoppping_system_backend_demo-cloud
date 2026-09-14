package com.mall.usercenter.client;

import com.mall.usercenter.support.dto.SkuSnapshotVO;
import com.mall.usercenter.support.dto.SpuSnapshotVO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品域出站客户端的<b>线格式契约测试</b>（不起 Spring、不连库、不依赖下游进程）。
 *
 * <p>为什么值得单独一个套件：这个客户端的风险全在"线上字节"上——路径拼错、请求体字段名不一致、
 * 或者把 {@code Map<Long,Long>} 的<b>字符串 key</b> 反序列化失败，都是编译期看不出来、
 * 只有真的发一次请求才会暴露的问题（P3-3 期间"最低价端点"就因此被误判成"上游不存在"）。
 * 这里用一个 JDK 自带的 {@link HttpServer} 起本地桩，逐条断言路径、请求体、内部凭据头与解析结果。
 *
 * <p>与真下游的一致性由桩里的 JSON 逐字对齐商品域 {@code InternalProductController} 的形状保证。
 */
class ProductSnapshotClientTest {

    private static HttpServer server;
    private static int port;

    private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_TOKEN = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ProductSnapshotClientTest::respond);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 桩：记录请求，按路径回放与商品域一致的最小 JSON */
    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        LAST_PATH.set(path);
        LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        LAST_TOKEN.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));

        String json = switch (path) {
            case "/internal/v1/product/sku/min-price/batch" ->
                    // key 是字符串形式的 spuId（Jackson 惯例），且"没有可用 SKU 的 SPU"不出现
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"1001\":129900,\"1002\":9900}}";
            case "/internal/v1/product/sku/batch" ->
                    "{\"code\":0,\"message\":\"ok\",\"data\":[{\"id\":2001,\"spuId\":1001,\"price\":129900,"
                            + "\"originalPrice\":159900,\"image\":\"/img/sku/2001.png\","
                            + "\"specValues\":\"[{\\\"name\\\":\\\"颜色\\\",\\\"value\\\":\\\"黑\\\"}]\","
                            + "\"stock\":20,\"status\":1}]}";
            case "/internal/v1/product/spu/batch" ->
                    "{\"code\":0,\"message\":\"ok\",\"data\":[{\"id\":1001,\"title\":\"手机\","
                            + "\"subtitle\":null,\"mainImage\":\"/img/spu/1001.png\",\"sales\":88,\"status\":1}]}";
            default -> "{\"code\":404,\"message\":\"no such endpoint\"}";
        };
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("[商品域契约] 最低价：POST /internal/v1/product/sku/min-price/batch（入参是 SPU id，字符串 key → Long）")
    void minPriceBatchMatchesUpstreamContract() {
        Map<Long, Long> prices = client(baseUrl()).minEnabledSkuPrices(List.of(1001L, 1002L));

        assertEquals("/internal/v1/product/sku/min-price/batch", LAST_PATH.get());
        assertEquals("{\"ids\":[1001,1002]}", LAST_BODY.get(), "请求体必须与 sku/batch 同形");
        assertEquals("test-internal-token", LAST_TOKEN.get(), "内部端点必须带 X-Internal-Token");
        assertEquals(129900L, prices.get(1001L).longValue());
        assertEquals(9900L, prices.get(1002L).longValue());
        assertFalse(prices.containsKey(999999L), "没有可用 SKU 的 SPU 不出现在结果里，调用方按 0 处理");
    }

    @Test
    @DisplayName("[商品域契约] 最低价：下游不可用 → 空 Map 降级（不抛异常，起售价回落 0）")
    void minPriceDegradesToEmptyMapWhenUnavailable() {
        // 端口 1 上没有服务 → 连接被拒；起售价是展示字段，不该让"我的收藏"整页报错
        assertTrue(client("http://127.0.0.1:1").minEnabledSkuPrices(List.of(1001L)).isEmpty());
    }

    @Test
    @DisplayName("[商品域契约] SKU/SPU 批量：路径、请求体与快照字段")
    void skuAndSpuBatchUseTheBatchEndpoints() {
        SkuSnapshotVO sku = client(baseUrl()).sku(2001L);
        assertEquals("/internal/v1/product/sku/batch", LAST_PATH.get());
        assertEquals("{\"ids\":[2001]}", LAST_BODY.get(), "单条查询也走批量端点（一种请求形状、一个超时点）");
        assertEquals(2001L, sku.getId().longValue());
        assertEquals(1001L, sku.getSpuId().longValue());
        assertEquals(129900L, sku.getPrice().longValue());
        assertEquals(20, sku.getStock().intValue());
        assertEquals(1, sku.getStatus().intValue());

        SpuSnapshotVO spu = client(baseUrl()).spu(1001L);
        assertEquals("/internal/v1/product/spu/batch", LAST_PATH.get());
        assertEquals("{\"ids\":[1001]}", LAST_BODY.get());
        assertEquals("手机", spu.getTitle());
        assertEquals("/img/spu/1001.png", spu.getMainImage());
        assertEquals(88, spu.getSales().intValue());
        assertEquals(1, spu.getStatus().intValue());
    }

    @Test
    @DisplayName("[商品域契约] 下游不可用：SKU/SPU 查询抛异常，不伪装成'没有这个商品'")
    void skuLookupFailsLoudlyWhenUnavailable() {
        // 与最低价的降级刻意不对称：库存/上下架是加购与购物车列表的**校验依据**，
        // 若把"下游挂了"当成"商品不存在"，用户会看到假的"商品已下架"。
        assertThrows(ProductUnavailableException.class, () -> client("http://127.0.0.1:1").sku(2001L));
    }

    // ---------- helpers ----------

    private static String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** 直连地址（非 lb://）时客户端自己建 builder，因此这里传一个普通 builder 即可 */
    private static ProductSnapshotClient client(String baseUrl) {
        return new ProductSnapshotClient(RestClient.builder(), baseUrl, "test-internal-token", 500, 2000);
    }
}
