package com.mall.demo.oms;

import com.mall.demo.common.BusinessException;
import com.mall.demo.common.contract.CouponCommandService;
import com.mall.demo.common.contract.CouponQueryService;
import com.mall.demo.oms.dto.OrderBuyNow;
import com.mall.demo.oms.dto.OrderCreateRequest;
import com.mall.demo.oms.service.OrderService;
import com.mall.demo.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交易侧的券链路（P5 步骤 C 之后）：<b>券的读写都在 marketing</b>，
 * 本套件只断言"交易域与券契约的交互 + 下单结果"。
 *
 * <h2>为什么这里用 {@link MockitoBean} 而不是真连 marketing</h2>
 * 券的**规则**（5 项校验、抵扣封顶、三态 CAS、后台规则）已经整体搬进营销域，
 * 那里有真库套件逐条覆盖（{@code mall-marketing: CouponQueryMySqlTest / CouponTriStateMySqlTest /
 * CouponMemberApiMySqlTest / InternalMarketingAdminCouponApiMySqlTest}）。
 * 单体侧再复制一份"券规则"的实现（哪怕只在测试里）就正好是 P0 反复消除的"两套口径"。
 * 因此这里把两个契约换成 Mockito：
 * <ul>
 *   <li>断言**契约交互**：`lock` 必须被调用、且 `orderNo` 就是本单的单号；成功路径**不得**调用 `unlock`；</li>
 *   <li>断言**透传**：营销域抛的 400/409 文案原样成为对外响应（不能被包成 500）；</li>
 *   <li>断言**下单结果**：金额、库存扣减次数、回滚补偿。</li>
 * </ul>
 * 真规则 + 真库 + 真跨进程调用由活体脚本（`.dsh-notes/p5-coupon-order-probe.ps1` 与步骤 C 的活体脚本）守。
 *
 * <p>⚠️ 单体侧的券种子数据**不再**插入任何表：本套件不读券表（券表已搬走），
 * 直插 {@code mall_marketing} 只会留下没人读的装饰性数据。这也是 {@code MySqlTestBase}
 * 的 {@code rememberCouponCount} 改指 {@code mall_marketing} 的原因——它服务的是别的套件。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CouponMySqlTest extends MySqlTestBase {

    /** 券查询契约（真实实现是 MarketingClient → marketing）；本套件只关心交互与透传 */
    @MockitoBean
    private CouponQueryService couponQueryService;

    /** 券命令契约（lock/unlock） */
    @MockitoBean
    private CouponCommandService couponCommandService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    @Test
    @DisplayName("[下单用券] discount 取营销域 → lock 成功 → 金额正确；成功路径不得 unlock")
    void couponFlowHappyPath() throws Exception {
        String token = registerAs("cpn_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(2001);
        int base2001 = stock(2001);
        when(couponQueryService.discountFor(anyLong(), eq(1L), anyLong())).thenReturn(1000L);
        when(couponCommandService.lock(anyLong(), eq(1L), any())).thenReturn(true);

        // 预览：抵扣来自营销域契约
        mockMvc.perform(post("/api/order/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                                + ",\"couponId\":1,\"buyNow\":{\"skuId\":2001,\"quantity\":1}}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.totalAmount").value(29900))
                .andExpect(jsonPath("$.data.discountAmount").value(1000))
                .andExpect(jsonPath("$.data.payAmount").value(28900));

        // 下单：金额正确 + 券被锁定（而不是核销——核销属步骤 E）
        String orderNo = buyNowWithCoupon(auth, addressId, 2001, 1L);
        mockMvc.perform(get("/api/order/" + orderNo).header("Authorization", auth))
                .andExpect(jsonPath("$.data.discountAmount").value(1000))
                .andExpect(jsonPath("$.data.payAmount").value(28900));

        // 契约交互：lock 的 orderNo 必须就是本单的单号（否则解锁/核销会找不到券）
        verify(couponCommandService).lock(memberId, 1L, orderNo);
        verify(couponCommandService, never()).unlock(any(), any(), any());
        assertStock(2001, base2001 - 1);
    }

    @Test
    @DisplayName("[重复用券] lock 返回 false → 409「优惠券已被使用或失效」逐字，且库存只扣一次")
    void duplicateCouponConflicts() throws Exception {
        String token = registerAs("cpn2_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(2001);
        int base2001 = stock(2001);
        when(couponQueryService.discountFor(anyLong(), eq(1L), anyLong())).thenReturn(1000L);
        // 第一次锁得上，第二次锁不上（等价于"这张券已经被别的订单用掉/锁住"）
        when(couponCommandService.lock(anyLong(), eq(1L), any())).thenReturn(true, false);

        buyNowWithCoupon(auth, addressId, 2001, 1L);
        mockMvc.perform(post("/api/order/create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                                + ",\"couponId\":1,\"buyNow\":{\"skuId\":2001,\"quantity\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("优惠券已被使用或失效"));
        assertStock(2001, base2001 - 1);   // 只扣了一单
    }

    @Test
    @DisplayName("[文案透传] 营销域的 400/409 原样成为对外响应（不被包成 500）")
    void businessMessagesPassThrough() throws Exception {
        String token = registerAs("cpn3_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);

        // 门槛不足：营销域抛 400「未满足优惠券使用门槛」→ 预览原样返回
        when(couponQueryService.discountFor(anyLong(), eq(9L), anyLong()))
                .thenThrow(new BusinessException(400, "未满足优惠券使用门槛"));
        mockMvc.perform(post("/api/order/preview").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                                + ",\"couponId\":9,\"buyNow\":{\"skuId\":2001,\"quantity\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("未满足优惠券使用门槛"));
    }

    @Test
    @DisplayName("[失败补偿] lock 之后落单失败（事务回滚）→ 必须调用 unlock，券回到 0")
    void unlockOnRollbackAfterLock() throws Exception {
        String token = registerAs("cpn4_");
        long addressId = createAddress(token);
        rememberStock(2001);
        int base2001 = stock(2001);
        when(couponQueryService.discountFor(anyLong(), eq(1L), anyLong())).thenReturn(1000L);
        when(couponCommandService.lock(anyLong(), eq(1L), any())).thenReturn(true);

        // 让 createOrder 跑在一个**外层事务**里，然后在它返回之后抛异常：
        // 这样"券已锁、但订单事务最终回滚"这个场景可以被确定性地造出来
        // （真实世界对应"落单失败/后续步骤异常"，活体脚本用超长 user_remark 触发同一条路径）。
        OrderCreateRequest request = new OrderCreateRequest();
        request.setSource("BUY_NOW");
        request.setAddressId(addressId);
        request.setCouponId(1L);
        OrderBuyNow buyNow = new OrderBuyNow();
        buyNow.setSkuId(2001L);
        buyNow.setQuantity(1);
        request.setBuyNow(buyNow);
        AtomicReference<String> createdOrderNo = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).execute(status -> {
            createdOrderNo.set(orderService.createOrder(memberId, request));
            throw new IllegalStateException("模拟落单后的失败");
        }));

        // 补偿：解锁必须被调用，且参数就是同一单（memberId, couponId, orderNo）
        verify(couponCommandService).unlock(memberId, 1L, createdOrderNo.get());
        // 事务真的回滚了：订单没落库、库存没被扣
        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oms_order WHERE order_no = ?", Integer.class, createdOrderNo.get());
        assertEquals(0, orders == null ? -1 : orders);
        assertStock(2001, base2001);
    }

    @Test
    @DisplayName("[不用券] couponId 为空 → 不碰券契约（discount 返回 0 的调用语义仍在契约里）")
    void withoutCouponSkipsCouponContract() throws Exception {
        String token = registerAs("cpn5_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        when(couponQueryService.discountFor(anyLong(), any(), anyLong())).thenReturn(0L);

        // 不用券下单：不传 couponId
        requestWithoutCoupon(auth, addressId, 2001);

        // 关键：**不传 couponId 时不应调用 lock**（否则会锁出一张没有订单归属的券）
        verify(couponCommandService, never()).lock(any(), any(), any());
    }

    // ---------- helpers ----------

    /** 下单（带券），返回订单号 */
    private String buyNowWithCoupon(String auth, long addressId, long skuId, long couponId) throws Exception {
        org.springframework.test.web.servlet.MvcResult r = mockMvc.perform(
                        post("/api/order/create").header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                                        + ",\"couponId\":" + couponId
                                        + ",\"buyNow\":{\"skuId\":" + skuId + ",\"quantity\":1}}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return trackOrder(r);
    }

    /** 下单（不带券） */
    private void requestWithoutCoupon(String auth, long addressId, long skuId) throws Exception {
        org.springframework.test.web.servlet.MvcResult r = mockMvc.perform(
                        post("/api/order/create").header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                                        + ",\"buyNow\":{\"skuId\":" + skuId + ",\"quantity\":1}}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        trackOrder(r);
    }
}
