package com.mall.demo.admin;

import com.jayway.jsonpath.JsonPath;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.client.SearchOpsClient;
import com.mall.demo.common.dto.ReindexResult;
import com.mall.demo.common.dto.SearchStatusVO;
import com.mall.demo.support.MySqlTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/admin/es/**} 的**纯转发**判据（P6-5 #6）。
 *
 * <h2>为什么必须换掉 {@code SearchOpsClient}（{@code @MockitoBean}）</h2>
 * 本类要证的**不是**"检索域答得对不对"（那是 {@code mall-search} 自己的套件 + 我跑过的活体判据），
 * 而是三件**单体侧**的事：
 * <ol>
 *   <li><b>形状等价（C1）</b>：转发之后，响应体的字段名/键路径/
 *       {@code @JsonInclude(NON_NULL)} 的"可用 / 不可用"两条分支，与改造前**逐字一致**
 *       —— 改造前这五个集群字段来自单体自己的 {@code ElasticsearchClient}，现在来自检索域，
 *       <b>来源变了、形状不能变</b>；</li>
 *   <li><b>两条"不可用"要分清</b>：检索域不可达（转发失败）与"检索域说 ES 不可用"（快照里集群字段为空）
 *       都必须报 {@code available=false + error}，而且**都不抛**（自检接口的语义是如实报告）
 *       —— 把自检做成 500 会让运维看不到原因，这是改造前后都必须守住的；</li>
 *   <li><b>重建不在本地发生</b>：{@code POST /product/reindex} 必须调用检索域客户端
 *       （D5：单体**不再有**任何写索引的能力；本地那份实现读的是冻结的 {@code mall} 库，
 *       会把旧文档写回索引 —— P6-4 §四点六 实测过的事故）。</li>
 * </ol>
 *
 * <h2>本类**不**覆盖什么</h2>
 * 真检索域的回答（集群名/版本/健康/节点数在真实 ES 上的值）由活体判据覆盖：
 * 重启单体后跑 C1 基线（34 项里含 {@code admin-es-ping}）并与 P6-4 的基线逐字比对；
 * 本类用桩是为了让"形状"这条断言不被 ES 的瞬时状态影响。
 *
 * <p>⚠️ 顺带记一条**从旧套件继承下来的事实**（那是实测数据，不是猜测）：全量重建的语义是
 * "删旧索引 → 建新索引 → bulk 写入"，重建窗口里集群会短暂处于 YELLOW（分片 initializing），
 * 自检会如实报告 {@code status=yellow}、{@code docCount} 甚至为 -1/0 —— 那是**中间态，不是故障**。
 * 所以对真实检索域断言"必须 green"时要允许等待（旧套件里是 {@code waitUntilGreen(15s)}），
 * 别把瞬时快照当成契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminEsForwardMySqlTest extends MySqlTestBase {

    private static final String PING = "/api/admin/es/ping";
    private static final String REINDEX = "/api/admin/es/product/reindex";

    /** 检索域运维客户端：换掉它，才能把"形状"与"检索域此刻的状态"解耦 */
    @MockitoBean
    private SearchOpsClient searchOpsClient;

    // P8-2a：后台身份不再"登录拿令牌"，而是网关的身份三件套（见 MySqlTestBase.adminHeaders()）——
    // 单体已经不再验签，拿 Authorization 打过来只会得到 401「未登录」。

    /** 检索域正常回答（值取真实环境实测过的一组：ES 9.5.3 / green / 1 节点） */
    private SearchStatusVO healthyStatus() {
        return new SearchStatusVO("mall_product", 1373L, 0L, "smartcn",
                "elasticsearch", "DESKTOP-J8HQ7NB", "9.5.3", "green", 1);
    }

    // ==================================================================
    // ① 可用分支：字段名与改造前逐字一致（含"error 不出现"这条 NON_NULL 行为）
    // ==================================================================

    @Test
    @DisplayName("[转发/C1] 检索域可用：11 个字段名与键路径逐字一致，error 因 NON_NULL **不出现**")
    void ping_availableBranch_keepsExactShape() throws Exception {
        when(searchOpsClient.status()).thenReturn(healthyStatus());

        String body = mockMvc.perform(get(PING).headers(adminHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.clusterName").value("elasticsearch"))
                .andExpect(jsonPath("$.data.nodeName").value("DESKTOP-J8HQ7NB"))
                .andExpect(jsonPath("$.data.esVersion").value("9.5.3"))
                .andExpect(jsonPath("$.data.status").value("green"))
                .andExpect(jsonPath("$.data.numberOfNodes").value(1))
                .andExpect(jsonPath("$.data.productIndex").value("mall_product"))
                .andExpect(jsonPath("$.data.productDocCount").value(1373))
                .andExpect(jsonPath("$.data.productTitleAnalyzer").value("smartcn"))
                .andExpect(jsonPath("$.data.productPendingSync").value(0))
                .andReturn().getResponse().getContentAsString();

        java.util.Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data.keySet())
                .as("可用分支的键集合必须与改造前一致：error 不出现（NON_NULL），也不许多出字段")
                .containsExactlyInAnyOrder("available", "clusterName", "nodeName", "esVersion", "status",
                        "numberOfNodes", "productIndex", "productDocCount", "productTitleAnalyzer",
                        "productPendingSync");
        assertThat(data.get("error")).as("可用时 error 必须是 null ⇒ 被 NON_NULL 剔除").isNull();
    }

    // ==================================================================
    // ② 不可用分支（两个来源）
    // ==================================================================

    @Test
    @DisplayName("[转发] 检索域活着但报告 ES 不可用（集群字段为空）：available=false + error，观测字段仍来自检索域")
    void ping_esDownBranch_reportsUnavailable() throws Exception {
        // ES 不可达时 search 的快照形状：集群字段 null/null/null/null/-1，但观测字段照常给
        when(searchOpsClient.status())
                .thenReturn(new SearchStatusVO("mall_product", -1L, 7L, "standard", null, null, null, null, -1));

        String body = mockMvc.perform(get(PING).headers(adminHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.productIndex").value("mall_product"))
                .andExpect(jsonPath("$.data.productDocCount").value(-1))
                .andExpect(jsonPath("$.data.productTitleAnalyzer").value("standard"))
                .andExpect(jsonPath("$.data.productPendingSync").value(7))
                .andReturn().getResponse().getContentAsString();

        java.util.Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data.keySet())
                .as("不可用分支：集群字段必须**不出现**（NON_NULL），error 必须出现")
                .containsExactlyInAnyOrder("available", "error", "productIndex", "productDocCount",
                        "productTitleAnalyzer", "productPendingSync");
        assertThat((String) data.get("error")).contains("Elasticsearch 不可用");
    }

    @Test
    @DisplayName("[转发] 检索域不可达：HTTP 200 + code=0 + available=false + error 带真实原因（绝不抛 500）")
    void ping_searchUnreachable_stillReturnsOk() throws Exception {
        when(searchOpsClient.status())
                .thenThrow(new BusinessException(500, "检索域不可达: Connection refused"));

        String body = mockMvc.perform(get(PING).headers(adminHeaders()))
                .andExpect(status().isOk())              // ⚠️ 自检失败不是接口失败
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.productIndex").value("mall_product"))
                .andExpect(jsonPath("$.data.productDocCount").value(-1))
                .andExpect(jsonPath("$.data.productTitleAnalyzer").value("standard"))
                .andExpect(jsonPath("$.data.productPendingSync").value(-1))
                .andReturn().getResponse().getContentAsString();

        java.util.Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data.keySet())
                .as("与 ES 不可用分支同形（同样的键集合，含 productIndex —— 改造前它是无条件输出的）")
                .containsExactlyInAnyOrder("available", "error", "productIndex", "productDocCount",
                        "productTitleAnalyzer", "productPendingSync");
        assertThat((String) data.get("error"))
                .as("error 必须带上真实原因（包装成「系统繁忙」等于把唯一的线索丢掉）")
                .contains("检索域不可达");
    }

    @Test
    @DisplayName("[转发/鉴权] 无管理员令牌 ⇒ 401「未登录」，且**不调用**检索域（鉴权在转发之前）")
    void ping_withoutAdminToken_is401_andNoForward() throws Exception {
        mockMvc.perform(get(PING))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        org.mockito.Mockito.verifyNoInteractions(searchOpsClient);
    }

    // ==================================================================
    // ③ 重建：必须转发（单体不再有写索引的能力）
    // ==================================================================

    @Test
    @DisplayName("[转发/D5] 全量重建：调用检索域客户端（返回值原样透传），单体不自己写索引")
    void reindex_forwardsToSearch() throws Exception {
        ReindexResult stub = new ReindexResult("mall_product", "smartcn", 1373L, 1373L, 1234L);
        when(searchOpsClient.reindex()).thenReturn(stub);

        mockMvc.perform(post(REINDEX).headers(adminHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.index").value("mall_product"))
                .andExpect(jsonPath("$.data.indexed").value(1373))
                .andExpect(jsonPath("$.data.onShelfTotal").value(1373))
                .andExpect(jsonPath("$.data.tookMillis").value(1234));

        verify(searchOpsClient).reindex();
    }
}
