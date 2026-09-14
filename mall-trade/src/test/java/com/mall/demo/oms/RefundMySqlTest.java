package com.mall.demo.oms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.mall.demo.support.MySqlTestBase;
import com.mall.demo.support.MySqlTestBase;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 退款售后真库测试(真实提交+显式清理恢复基线)。
 * 覆盖：仅退款(未发货)/退货退款(已收货)/重复申请/拒绝/用户取消/回寄物流/库存回补/订单退款联动。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundMySqlTest extends MySqlTestBase {




    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    @Test
    @DisplayName("[MySQL] 仅退款(未发货)：同意后模拟退款+库存回补+订单已退款(7)")
    void onlyMoneyRefund() throws Exception {
        String token = registerAs("ref_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        int base = stock(2001);
        rememberStock(2001);
        String orderNo = createPaidOrder(token, addressId, 2001, 1);
        assertStock(2001, base - 1);

        String refundNo = applyRefund(auth, orderNo, 1, "不想要了");

        // 申请阶段：订单仍是待发货(1)，但售后已在处理中
        mockMvc.perform(get("/api/order/" + orderNo).header("Authorization", auth))
                .andExpect(jsonPath("$.data.orderStatus").value(1))
                .andExpect(jsonPath("$.data.refund.status").value(0))
                .andExpect(jsonPath("$.data.refund.statusText").value("待处理"));

        // 重复申请被拦
        mockMvc.perform(post("/api/refund/apply").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"refundType\":1,\"reason\":\"again\"}"))
                .andExpect(jsonPath("$.code").value(409));


        // 后台找到并同意(仅退款=直接完成)
        long refundId = firstRefundId();
        mockMvc.perform(post("/api/admin/refund/" + refundId + "/approve").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));

        // 售后完成
        mockMvc.perform(get("/api/refund/" + refundNo).header("Authorization", auth))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.statusText").value("已完成"));
        // 订单已退款(7)且全额退款
        mockMvc.perform(get("/api/order/" + orderNo).header("Authorization", auth))
                .andExpect(jsonPath("$.data.payStatus").value(2))
                .andExpect(jsonPath("$.data.orderStatus").value(7))
                .andExpect(jsonPath("$.data.statusText").value("已退款"));
        // 库存回补
        assertStock(2001, base);

        // 「退款/售后」标签页：收录有售后单的订单，并带售后摘要
        mockMvc.perform(get("/api/order/page").header("Authorization", auth).param("afterSale", "true"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.list[0].refundNo").value(refundNo))
                .andExpect(jsonPath("$.data.list[0].refundStatusText").value("已完成"));
        // 按状态筛选：7 已退款 命中，6 退款中 无
        mockMvc.perform(get("/api/order/page").header("Authorization", auth).param("status", "7"))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/order/page").header("Authorization", auth).param("status", "6"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("[MySQL] 退货退款(已收货)：同意→回寄→收货确认→退款+库存回补")
    void returnGoodsRefund() throws Exception {
        String token = registerAs("ref_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        int base = stock(2002);
        rememberStock(2002);
        rememberSales("pms_sku", 2002);
        rememberSales("pms_spu", 1002);

        String orderNo = createPaidOrder(token, addressId, 2002, 1);
        // 发货→收货→已完成
        mockMvc.perform(post("/api/admin/order/" + orderNo + "/ship").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logisticsCompany\":\"顺丰\",\"logisticsNo\":\"SF1\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/order/" + orderNo + "/confirm").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0));

        String refundNo = applyRefund(auth, orderNo, 2, "质量问题");
        long refundId = firstRefundId();

        // 同意 → 处理中(等待回寄)，订单进入「退款中(6)」
        mockMvc.perform(post("/api/admin/refund/" + refundId + "/approve").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/refund/" + refundNo).header("Authorization", auth))
                .andExpect(jsonPath("$.data.status").value(1));
        mockMvc.perform(get("/api/order/" + orderNo).header("Authorization", auth))
                .andExpect(jsonPath("$.data.orderStatus").value(6))
                .andExpect(jsonPath("$.data.statusText").value("退款中"));

        // 用户填写回寄物流
        mockMvc.perform(post("/api/refund/" + refundNo + "/return-logistics").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"returnCompany\":\"中通快递\",\"returnTrackingNo\":\"ZT123456\"}"))
                .andExpect(jsonPath("$.code").value(0));

        // 卖家确认收到退货 → 完成退款
        mockMvc.perform(post("/api/admin/refund/" + refundId + "/received").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/refund/" + refundNo).header("Authorization", auth))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.returnTrackingNo").value("ZT123456"));
        // 订单标记已退款(7) + 全额退款
        mockMvc.perform(get("/api/order/" + orderNo).header("Authorization", auth))
                .andExpect(jsonPath("$.data.payStatus").value(2))
                .andExpect(jsonPath("$.data.orderStatus").value(7))
                .andExpect(jsonPath("$.data.refund.returnTrackingNo").value("ZT123456"));
        // 库存回补
        assertStock(2002, base);
    }

    @Test
    @DisplayName("[MySQL] 拒绝售后 + 用户取消申请")
    void rejectAndCancel() throws Exception {
        String token = registerAs("ref_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(2001);

        // 被拒绝的单
        String orderNo1 = createPaidOrder(token, addressId, 2001, 1);
        String refund1 = applyRefund(auth, orderNo1, 1, "不想要");
        long id1 = firstRefundId();
        // 参数校验(本轮新增)：拒绝原因必填；空值 400 且售后单状态不变
        mockMvc.perform(post("/api/admin/refund/" + id1 + "/reject").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请填写拒绝原因"));
        mockMvc.perform(post("/api/admin/refund/" + id1 + "/reject").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不符合退款条件\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/refund/" + refund1).header("Authorization", auth))
                .andExpect(jsonPath("$.data.status").value(3))
                .andExpect(jsonPath("$.data.statusText").value("已拒绝"));

        // 用户取消的单(待处理时取消)
        String orderNo2 = createPaidOrder(token, addressId, 2001, 1);
        String refund2 = applyRefund(auth, orderNo2, 1, "改主意了");
        mockMvc.perform(post("/api/refund/" + refund2 + "/cancel").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/refund/" + refund2).header("Authorization", auth))
                .andExpect(jsonPath("$.data.status").value(4));

        // 两个订单都没有被退款，订单状态保持待发货(1)
        mockMvc.perform(get("/api/order/" + orderNo1).header("Authorization", auth))
                .andExpect(jsonPath("$.data.payStatus").value(1))
                .andExpect(jsonPath("$.data.orderStatus").value(1))
                .andExpect(jsonPath("$.data.refund.statusText").value("已拒绝"));
        mockMvc.perform(get("/api/order/" + orderNo2).header("Authorization", auth))
                .andExpect(jsonPath("$.data.payStatus").value(1))
                .andExpect(jsonPath("$.data.orderStatus").value(1))
                .andExpect(jsonPath("$.data.refund.statusText").value("已取消"));
        // 「退款/售后」标签页仍能看到这两单(被拒/已撤销也算售后)
        mockMvc.perform(get("/api/order/page").header("Authorization", auth).param("afterSale", "true"))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    // ---------- helpers ----------




    /** 下单并支付，返回 orderNo(已登记清理) */
    private String createPaidOrder(String token, long addressId, long skuId, int qty) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/order/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                                + ",\"buyNow\":{\"skuId\":" + skuId + ",\"quantity\":" + qty + "}}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String orderNo = com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.data");
        orderNos.add(orderNo);
        mockMvc.perform(post("/api/pay/mock").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"success\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return orderNo;
    }

    private String applyRefund(String auth, String orderNo, int type, String reason) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/refund/apply").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"refundType\":" + type + ",\"reason\":\"" + reason + "\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.data");
    }

    private long firstRefundId() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/admin/refund/page").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String c = r.getResponse().getContentAsString();
        Number n = com.jayway.jsonpath.JsonPath.read(c, "$.data.list[0].id");
        return n.longValue();
    }





}
