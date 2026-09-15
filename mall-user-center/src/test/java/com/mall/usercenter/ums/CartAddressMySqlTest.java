package com.mall.usercenter.ums;

import com.mall.usercenter.client.ProductSnapshotClient;
import com.mall.usercenter.support.UserCenterTestBase;
import com.mall.usercenter.support.constant.EnableStatus;
import com.mall.usercenter.support.dto.SkuSnapshotVO;
import com.mall.usercenter.support.dto.SpuSnapshotVO;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.mall.common.support.MemberId;

/**
 * 购物车 + 收货地址 真库测试（{@code mall_user}）。
 *
 * <p>从单体 {@code com.mall.demo.ums.CartAddressMySqlTest} 平移，两处**必须**的改造：
 * <ol>
 *   <li><b>身份来自网关注入头</b>：不再 {@code registerAs(...)} 拿 token 走 {@code Authorization}，
 *       而是 {@link #asMember} 直接给 {@code X-Gateway-Auth}+{@code X-Member-Id}——
 *       这正是拆分后的生产链路（网关验签一次，服务只认身份头）；</li>
 *   <li><b>商品数据来自商品域</b>：{@code pms_sku}/{@code pms_spu} 已不在本服务的库里
 *       （{@link ProductSnapshotClient} 是出站调用），因此用 {@code @MockitoBean} 提供确定性快照——
 *       断言的是"购物车怎么用商品数据"（库存上限、失效标记、规格文本），
 *       而不是"商品域的数据长什么样"（那是商品域自己的用例该管的）。</li>
 * </ol>
 * 业务断言（登录态、默认地址置顶、越权 404、库存上限 409、数量累加、角标件数）逐条保留。
 */
class CartAddressMySqlTest extends UserCenterTestBase {

    private static final long SKU_ID = 2001L;
    private static final long SPU_ID = 1001L;
    private static final int STOCK = 20;

    @MockitoBean
    private ProductSnapshotClient productSnapshotClient;

    private long memberA;
    private long memberB;

    @BeforeEach
    void setUp() {
        SkuSnapshotVO sku = new SkuSnapshotVO(SKU_ID, SPU_ID, 129900L, 159900L, "/img/sku/2001.png",
                "[{\"name\":\"颜色\",\"value\":\"曜石黑\"},{\"name\":\"存储\",\"value\":\"256G\"}]",
                STOCK, EnableStatus.ENABLED);
        SpuSnapshotVO spu = new SpuSnapshotVO(SPU_ID, "集成测试手机", "副标题", "/img/spu/1001.png", 88,
                EnableStatus.ENABLED);
        when(productSnapshotClient.sku(SKU_ID)).thenReturn(sku);
        when(productSnapshotClient.skus(anyCollection())).thenReturn(List.of(sku));
        when(productSnapshotClient.spu(SPU_ID)).thenReturn(spu);
        when(productSnapshotClient.spus(anyCollection())).thenReturn(List.of(spu));
        when(productSnapshotClient.minEnabledSkuPrices(anyCollection())).thenReturn(Map.of(SPU_ID, 129900L));

        memberA = insertMember("uc_carta_");
        memberB = insertMember("uc_cartb_");
    }

    @AfterEach
    void cleanUp() {
        deleteMember(memberA);
        deleteMember(memberB);
    }

    @Test
    @DisplayName("[MySQL] 未登录 / 伪造身份头访问购物车、地址 → 401")
    void requireLogin() throws Exception {
        // ① 什么都没有：未登录
        mockMvc.perform(get("/api/cart/list")).andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/address/list")).andExpect(jsonPath("$.code").value(401));

        // ② 客户端自己伪造 X-Member-Id（没有网关共享凭据）→ 仍然未登录。
        //    这条断言是"身份只认网关注入头"的可执行证据：能靠手写头冒充别人就等于没有鉴权。
        mockMvc.perform(get("/api/cart/list").header(GW_MEMBER_ID_HEADER, String.valueOf(memberA)))
                .andExpect(jsonPath("$.code").value(401));

        // ③ 网关凭据写错 → 同样 401（不区分"没带"与"带错"）
        mockMvc.perform(get("/api/cart/list")
                        .header(GW_AUTH_HEADER, "not-the-gateway-token")
                        .header(GW_MEMBER_ID_HEADER, String.valueOf(memberA)))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("[MySQL] 地址 CRUD + 默认地址置顶 + 他人不可见")
    void addressFlow() throws Exception {
        long id1 = createAddress(memberA, "张三", 1, true);
        long id2 = createAddress(memberA, "李四", 2, false);

        // 列表：默认地址(1)在前
        mockMvc.perform(asMember(get("/api/address/list"), memberA))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(id1))
                .andExpect(jsonPath("$.data[0].isDefault").value(1))
                .andExpect(jsonPath("$.data[0].memberId").value(memberA))
                .andExpect(jsonPath("$.data[0].receiverPhone").isNotEmpty())
                .andExpect(jsonPath("$.data[0].provinceName").value("北京市"))
                .andExpect(jsonPath("$.data[0].districtName").value("朝阳区"))
                .andExpect(jsonPath("$.data[0].detail").isNotEmpty());

        // 改默认 → 地址2置顶且地址1不再默认
        mockMvc.perform(asMember(put("/api/address/" + id2 + "/default"), memberA))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(get("/api/address/list"), memberA))
                .andExpect(jsonPath("$.data[0].id").value(id2))
                .andExpect(jsonPath("$.data[1].isDefault").value(0));

        // 更新
        mockMvc.perform(asMember(put("/api/address/" + id2), memberA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverName\":\"李四改\",\"receiverPhone\":\"13900139000\",\"detail\":\"新地址\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(get("/api/address/" + id2), memberA))
                .andExpect(jsonPath("$.data.receiverName").value("李四改"));

        // 越权：B 看不到/改不了 A 的地址（水平越权防护仍在地址域内）
        mockMvc.perform(asMember(get("/api/address/" + id1), memberB))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(asMember(delete("/api/address/" + id1), memberB))
                .andExpect(jsonPath("$.code").value(404));

        // 删除
        mockMvc.perform(asMember(delete("/api/address/" + id1), memberA))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(delete("/api/address/" + id2), memberA))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(get("/api/address/list"), memberA))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("[MySQL] 购物车：加购累加/数量/勾选/全选/清空/库存上限/越权")
    void cartFlow() throws Exception {
        // 加购两次 → 数量累加为 3
        mockMvc.perform(asMember(post("/api/cart/add"), memberA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_ID + ",\"quantity\":2}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(post("/api/cart/add"), memberA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_ID + ",\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(0));

        MvcResult listResult = mockMvc.perform(asMember(get("/api/cart/list"), memberA))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].quantity").value(3))
                .andExpect(jsonPath("$.data[0].skuId").value(SKU_ID))
                .andExpect(jsonPath("$.data[0].spuId").value(SPU_ID))
                .andExpect(jsonPath("$.data[0].title").value("集成测试手机"))
                .andExpect(jsonPath("$.data[0].skuName").value("曜石黑 / 256G"))
                .andExpect(jsonPath("$.data[0].price").value(129900))
                .andExpect(jsonPath("$.data[0].stock").value(STOCK))
                .andExpect(jsonPath("$.data[0].invalid").value(false))
                .andReturn();
        long itemId = readListId(listResult, 0);

        // 改数量
        mockMvc.perform(asMember(put("/api/cart/item/" + itemId), memberA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(jsonPath("$.code").value(0));
        // 超过库存：取"当前库存 + 1"而非写死数值，与商品域快照解耦(且不低于数量上限 99)
        int overStock = Math.min(STOCK + 1, 100);
        mockMvc.perform(asMember(put("/api/cart/item/" + itemId), memberA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + overStock + "}"))
                .andExpect(jsonPath("$.code").value(409));

        // 勾选/取消/全选
        mockMvc.perform(asMember(put("/api/cart/item/" + itemId + "/checked"), memberA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":false}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(get("/api/cart/list"), memberA))
                .andExpect(jsonPath("$.data[0].checked").value(false));
        mockMvc.perform(asMember(put("/api/cart/checked-all"), memberA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        // 角标数量（总件数）
        mockMvc.perform(asMember(get("/api/cart/count"), memberA))
                .andExpect(jsonPath("$.data").value(5));

        // 越权：B 操作 A 的条目 → 404
        mockMvc.perform(asMember(delete("/api/cart/item/" + itemId), memberB))
                .andExpect(jsonPath("$.code").value(404));
        // 越权：B 的购物车看不到 A 的条目（会员维度隔离）
        mockMvc.perform(asMember(get("/api/cart/list"), memberB))
                .andExpect(jsonPath("$.data.length()").value(0));

        // 删除条目
        mockMvc.perform(asMember(delete("/api/cart/item/" + itemId), memberA))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(get("/api/cart/count"), memberA))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    @DisplayName("[MySQL] 商品域说'没有这个 SKU/SPU' → 400，且不落购物车")
    void addMissingSkuRejected() throws Exception {
        when(productSnapshotClient.sku(SKU_ID)).thenReturn(null);
        mockMvc.perform(asMember(post("/api/cart/add"), memberA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_ID + ",\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("商品不存在或已下架"));
        org.junit.jupiter.api.Assertions.assertEquals(0L, cartRows(memberA));
    }

    // ---------- helpers ----------

    private long createAddress(long memberId, String name, int idx, boolean def) throws Exception {
        MvcResult result = mockMvc.perform(asMember(post("/api/address"), memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverName\":\"" + name + "\",\"receiverPhone\":\"1380013800"
                                + (1 + idx % 9) + "\",\"provinceCode\":\"110000\",\"provinceName\":\"北京市\","
                                + "\"cityCode\":\"110100\",\"cityName\":\"北京市\",\"districtCode\":\"110105\","
                                + "\"districtName\":\"朝阳区\",\"detail\":\"测试路 " + idx + " 号\",\"isDefault\":"
                                + def + "}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return readLong(result);
    }

    private static long readListId(MvcResult result, int index) throws Exception {
        Number n = JsonPath.read(result.getResponse().getContentAsString(), "$.data[" + index + "].itemId");
        return n.longValue();
    }

    private static long readLong(MvcResult result) throws Exception {
        Number n = JsonPath.read(result.getResponse().getContentAsString(), "$.data");
        return n.longValue();
    }
}
