package com.mall.trade.oms;

import com.mall.trade.oms.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.mall.trade.support.MySqlTestBase;
import java.util.ArrayList;
import com.mall.trade.support.MySqlTestBase;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单主链路真库测试(真实提交，@AfterEach 显式清理并恢复库存/销量基线)。
 * seed 商品 SKU 2001(库存50,价29900) / 2002(库存30,价29900)。
 *
 * <p><b>P5 步骤 C：券契约由全测试级替身兜底，本类不再自己 mock</b>
 * 结算预览（{@code POST /api/order/preview}）会无条件取一次"本单可用的券"
 * （{@code CouponQueryService.usableCoupons}），而该契约此刻是**跨进程**的
 * （{@code MarketingClient} → {@code mall-marketing}）。本套件验的是下单主链路（库存/闸门/状态流转），
 * 与券无关——因此由 {@code support/MarketingTestDoubleConfig}（测试期默认实现：无可用券、
 * 抵扣 0、lock/unlock=true）统一顶上，保证"CI 里没有 marketing 进程也能跑完整套回归"。
 *
 * <p>⚠️ 券本身的规则与链路**不是**在这里测：真规则在营销域真库套件，
 * 交易域与券契约的交互在下单券用例 {@link com.mall.trade.oms.CouponMySqlTest}（用 {@code @MockitoBean}
 * 覆盖替身并断言交互）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderFlowMySqlTest extends MySqlTestBase {

    @Autowired
    private OrderService orderService;

    private final List<String[]> restoreStock = new ArrayList<>();   // [skuId, stock]
    private final List<String[]> restoreSales = new ArrayList<>();   // [table,id,sales]

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    @Test
    @DisplayName("[MySQL] 购物车下单→支付→收货→取消回补→超时关单 全链路")
    void fullOrderFlow() throws Exception {
        String token = registerAs("ord_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);

        int base2001 = stock(2001);
        int base2002 = stock(2002);
        rememberStock(2001);
        rememberStock(2002);
        rememberSales("pms_sku", 2001);
        rememberSales("pms_sku", 2002);
        rememberSales("pms_spu", 1001);

        // ---- 购物车两件 ----
        // P3-4：/api/cart/** 已随购物车域搬去 user-center，购物车明细直接写属主库（mall_user.ums_cart_item）；
        // 下单链路仍然经域契约（远程=user-center 内部接口）读它、并以两阶段闸门 claim/restore 领取。
        long item1 = addCartItem(2001, 2);
        long item2 = addCartItem(2002, 1);

        // ---- 预览 ----
        mockMvc.perform(post("/api/order/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"CART\",\"cartItemIds\":[" + item1 + "," + item2 + "],\"addressId\":" + addressId + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(89700))
                .andExpect(jsonPath("$.data.defaultAddress.receiverName").value("张三"));

        // ---- 下单：库存预占 ----
        MvcResult created = mockMvc.perform(post("/api/order/create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"CART\",\"cartItemIds\":[" + item1 + "," + item2 + "],\"addressId\":" + addressId + "}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String orderNo1 = trackOrder(created);
        assertStock(2001, base2001 - 2);
        assertStock(2002, base2002 - 1);
        // 结算闸门把明细从购物车里领走了（原来用 GET /api/cart/count 断言，现在直接看属主库）
        assertThat(cartItemCount()).as("下单后购物车明细应被领取/清空").isZero();

        // ---- 详情/列表 ----
        mockMvc.perform(get("/api/order/" + orderNo1).header("Authorization", auth))
                .andExpect(jsonPath("$.data.statusText").value("待支付"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.payAmount").value(89700));
        mockMvc.perform(get("/api/order/page").header("Authorization", auth).param("status", "0"))
                .andExpect(jsonPath("$.data.list[0].orderNo").value(orderNo1));

        // ---- 模拟支付成功(幂等) ----
        pay(auth, orderNo1);
        pay(auth, orderNo1);   // 重复支付幂等
        mockMvc.perform(get("/api/pay/result/" + orderNo1).header("Authorization", auth))
                .andExpect(jsonPath("$.data.payStatus").value(1));
        mockMvc.perform(get("/api/order/" + orderNo1).header("Authorization", auth))
                .andExpect(jsonPath("$.data.orderStatus").value(1));

        // 支付后不可取消
        mockMvc.perform(post("/api/order/" + orderNo1 + "/cancel").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(409));

        // ---- 模拟发货(直改库)→确认收货(销量累加) ----
        int salesBefore = skuSales(2001);
        jdbcTemplate.update("UPDATE oms_order SET order_status=2, logistics_company='顺丰', logistics_no='SF1' WHERE order_no=?",
                orderNo1);
        mockMvc.perform(post("/api/order/" + orderNo1 + "/confirm").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/order/" + orderNo1).header("Authorization", auth))
                .andExpect(jsonPath("$.data.orderStatus").value(3));
        assertSales(2001, salesBefore + 2);

        // ---- 待支付取消：库存回补 ----
        int mid2002 = stock(2002);
        String orderNo2 = buyNow(token, addressId, 2002, 1);
        assertStock(2002, mid2002 - 1);
        mockMvc.perform(post("/api/order/" + orderNo2 + "/cancel").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0));
        assertStock(2002, mid2002);

        // ---- 超时关单：回补库存 ----
        int mid2001 = stock(2001);
        String orderNo3 = buyNow(token, addressId, 2001, 1);
        assertStock(2001, mid2001 - 1);
        jdbcTemplate.update("UPDATE oms_order SET pay_expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE order_no=?",
                orderNo3);
        orderService.closeExpiredOrders();
        mockMvc.perform(get("/api/order/" + orderNo3).header("Authorization", auth))
                .andExpect(jsonPath("$.data.orderStatus").value(4));
        assertStock(2001, mid2001);
    }

    // ---------- helpers ----------

    /**










    /**
     * 未登录提交订单：必须 401，且**一行数据都不能落**（订单/明细/库存流水/库存）。
     *
     * <p>这是写接口最该钉死的契约：鉴权发生在 Controller 第一行
     * （{@code memberSession.requireUserId(auth)}），早于任何库存扣减与落库，
     * 所以未登录请求既不占库存也不产生订单；同时它比限流切面晚一步失败，限流仍按 IP 生效（防"不登录刷接口"）。
     */
    @Test
    @DisplayName("[MySQL] 未登录/无效 token 提交订单 → 401 且不产生任何数据")
    void createOrderWithoutLogin_isRejectedWithoutSideEffect() throws Exception {
        long ordersBefore = countOf("oms_order");
        long itemsBefore = countOf("oms_order_item");
        long logsBefore = stockLogCount(2001);
        int stockBefore = stock(2001);

        String body = "{\"source\":\"BUY_NOW\",\"addressId\":1,\"buyNow\":{\"skuId\":2001,\"quantity\":1}}";

        // ① 完全不带 Authorization
        mockMvc.perform(post("/api/order/create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        // ② 头格式错（缺 Bearer 前缀）
        mockMvc.perform(post("/api/order/create").header("Authorization", "abc.def.ghi")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(401));

        // ③ 结构合法但签名伪造（暴力改签名段）
        mockMvc.perform(post("/api/order/create")
                        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                                + ".eyJzdWIiOjEsInR5cCI6InVzZXIiLCJ2ZXIiOjAsImV4cCI6NDEwMjQ0NDgwMH0"
                                + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(401));

        // ④ 预览 / 支付 / 我的订单 同样要求登录
        mockMvc.perform(post("/api/order/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/pay/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"20260101000000000001\",\"success\":true}"))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/order/page")).andExpect(jsonPath("$.code").value(401));

        // 关键断言：全部被拒之后，库里没有任何变化
        assertThat(countOf("oms_order")).as("未登录下单不应产生订单").isEqualTo(ordersBefore);
        assertThat(countOf("oms_order_item")).as("未登录下单不应产生订单明细").isEqualTo(itemsBefore);
        assertThat(stockLogCount(2001)).as("未登录下单不应产生库存流水").isEqualTo(logsBefore);
        assertStock(2001, stockBefore);
    }

    private long countOf(String table) {
        Long v = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return v == null ? -1L : v;
    }

    private long stockLogCount(long skuId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mall_product.pms_sku_stock_log WHERE sku_id = ?", Long.class, skuId);
        return v == null ? -1L : v;
    }
}