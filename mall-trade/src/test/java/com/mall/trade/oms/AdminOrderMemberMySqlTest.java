package com.mall.trade.oms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mall.trade.support.MySqlTestBase;
import org.springframework.test.web.servlet.MvcResult;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.MemberId;

/**
 * 后台订单管理 + 后台会员管理 真库测试(真实提交+显式清理恢复基线)。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderMemberMySqlTest extends MySqlTestBase {




    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    @Test
    @DisplayName("[MySQL] 后台：关闭未支付订单(回补库存) + 支付后发货 + 会员查询/禁用即失效")
    void adminOrderAndMemberFlow() throws Exception {
        String token = registerAs("adm_");
        String auth = "Bearer " + token;
        long addressId = createAddress(token);
        rememberStock(2001);
        rememberStock(2002);
        int base2001 = stock(2001);
        int base2002 = stock(2002);

        // 未支付订单(后台关闭→回补)
        String order1 = buyNow(token, addressId, 2001, 1);
        assertStock(2001, base2001 - 1);

        // 已支付订单(后台发货)
        String order2 = buyNow(token, addressId, 2002, 1);
        assertStock(2002, base2002 - 1);
        pay(auth, order2);

        // P8-2a：后台身份来自网关的头（三件套），不再"登录拿令牌"——单体已不再自行验签
        // 后台订单查询：按会员昵称找到两单
        mockMvc.perform(get("/api/admin/order/page").headers(adminHeaders())
                        .param("memberKeyword", username))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2));
        // 按状态 1 只看到已支付待发货的 order2
        mockMvc.perform(get("/api/admin/order/page").headers(adminHeaders())
                        .param("status", "1"))
                .andExpect(jsonPath("$.data.list[0].orderNo").value(order2))
                .andExpect(jsonPath("$.data.list[0].memberName").value(username));

        // 按支付状态筛选：未支付 1 单 / 已支付 1 单
        mockMvc.perform(get("/api/admin/order/page").headers(adminHeaders())
                        .param("memberKeyword", username).param("payStatus", "0"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].orderNo").value(order1));
        mockMvc.perform(get("/api/admin/order/page").headers(adminHeaders())
                        .param("memberKeyword", username).param("payStatus", "1"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].orderNo").value(order2));

        // 按下单日期范围筛选(今天应含两单；昨天应 0 单)
        String today = java.time.LocalDate.now().toString();
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        mockMvc.perform(get("/api/admin/order/page").headers(adminHeaders())
                        .param("memberKeyword", username)
                        .param("createDateStart", today).param("createDateEnd", today))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/admin/order/page").headers(adminHeaders())
                        .param("memberKeyword", username)
                        .param("createDateStart", yesterday).param("createDateEnd", yesterday))
                .andExpect(jsonPath("$.data.total").value(0));

        // 后台详情
        mockMvc.perform(get("/api/admin/order/" + order2).headers(adminHeaders()))
                .andExpect(jsonPath("$.data.memberUsername").value(username))
                .andExpect(jsonPath("$.data.payStatus").value(1));

        // 关闭未支付订单 → 已关闭 + 库存回补
        mockMvc.perform(post("/api/admin/order/" + order1 + "/close").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"缺货\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/admin/order/" + order1).headers(adminHeaders()))
                .andExpect(jsonPath("$.data.orderStatus").value(5));
        assertStock(2001, base2001);

        // 参数校验(本轮新增)：物流公司与单号、关闭原因均为必填；空值返回 400 且订单状态不变
        mockMvc.perform(post("/api/admin/order/" + order2 + "/ship").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logisticsCompany\":\"\",\"logisticsNo\":\"SF123456\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请填写物流公司"));
        mockMvc.perform(post("/api/admin/order/" + order2 + "/close").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请填写关闭原因"));
        mockMvc.perform(get("/api/admin/order/" + order2).headers(adminHeaders()))
                .andExpect(jsonPath("$.data.orderStatus").value(1));   // 仍是待发货：校验失败不该动数据

        // 已支付订单发货 → 待收货 + 物流
        mockMvc.perform(post("/api/admin/order/" + order2 + "/ship").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logisticsCompany\":\"顺丰速运\",\"logisticsNo\":\"SF123456\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/admin/order/" + order2).headers(adminHeaders()))
                .andExpect(jsonPath("$.data.orderStatus").value(2))
                .andExpect(jsonPath("$.data.logisticsCompany").value("顺丰速运"));

        // 后台会员：查询/详情统计
        mockMvc.perform(get("/api/admin/member/page").headers(adminHeaders())
                        .param("keyword", username))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
        // 注册日期范围筛选：今天 1 个；昨天 0 个
        mockMvc.perform(get("/api/admin/member/page").headers(adminHeaders())
                        .param("keyword", username)
                        .param("createDateStart", today).param("createDateEnd", today))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/admin/member/page").headers(adminHeaders())
                        .param("keyword", username)
                        .param("createDateStart", yesterday).param("createDateEnd", yesterday))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/admin/member/" + memberId).headers(adminHeaders()))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.orderCount").value(2))        // 两单(含已支付)
                .andExpect(jsonPath("$.data.totalPaid").value(29900));

        // 禁用会员 → 旧 token 立即失效
        mockMvc.perform(put("/api/admin/member/" + memberId + "/status").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/order/page").header("Authorization", auth))
                .andExpect(jsonPath("$.code").value(401));

        // 恢复启用 → 旧 token 是否仍失效取决于"令牌版本号"(Redis 可用时才生效)：
        //   Redis 可用：禁用时版本号已 +1，旧 token 不会复活(避免历史 token 复活)
        //   Redis 不可用：按 fail-open 降级为"不做主动失效"，旧 token 会重新可用
        mockMvc.perform(put("/api/admin/member/" + memberId + "/status").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        if (redisUp()) {
            mockMvc.perform(get("/api/order/page").header("Authorization", auth))
                    .andExpect(jsonPath("$.code").value(401));
        }

        // 重新登录 → 新 token(版本号已同步)可正常访问(两种环境都成立)。
        // P3-4：登录在 user-center，单体侧用"按当前版本号本地签发"来表达同一件事（claims 口径不变）。
        String freshToken = mintTokenForCurrentMember();
        mockMvc.perform(get("/api/order/page").header("Authorization", "Bearer " + freshToken))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- helpers ----------








}
