package com.mall.product.admin;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.service.ProductSearchService;
import com.mall.product.support.JsonKit;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * <b>"索引同步必须发生在事务提交之后"的机器证据</b>（P6-5 #7 的残留项：单测形式的判据）。
 *
 * <h2>为什么必须有这条（P6-4 窗口实测的那个缺陷）</h2>
 * 后台"逻辑删除商品"之后，索引里可能仍留着该商品的文档（实测 {@code _id=22136}：库里 {@code status=1 deleted=1}、
 * 索引里还在、product 的 index-docs 对它返回 {@code docs:[]}）。机制是：
 * <pre>
 *   行内调 syncLater → search 侧**回读 product 的库**组装文档 → 若回读发生在**本地事务提交之前**，
 *   它读到"还没删"的状态 ⇒ 把文档保住/写回索引，提交后**没有任何重试** ⇒ 用户能搜到、点进去 404。
 * </pre>
 * 修法是让同步等 {@code afterCommit}（见 {@code support/TxCallbacks}）。本类把这条修法变成**可执行判据**：
 * 每次同步调用发生时，用**一条新连接**去读库 —— 新连接（非事务绑定）只能读到**已提交**的版本，
 * 所以"读到的状态里删除已经生效"就等价于"同步确实在提交之后"。若有人把同步改回行内调用，
 * 新连接读到的是删除**之前**的版本 ⇒ 本类立刻变红（这就是"环回桩记录调用时刻 vs 提交时刻"）。
 *
 * <h2>为什么用 {@code @MockitoBean} 换掉 {@code ProductSearchService}</h2>
 * 本类要断言的**不是**"search 能不能正确回读"（那是 P6-3/P6-2 的事，且已有真服务用例），
 * 而是"**调用时刻**与提交时刻的先后"。把搜索域换成一个记录器，才能把时刻钉死在一次断言里。
 * 真实链路（真 8102 + 真 broker + 真 ES + 真索引审计）由活体探针负责：
 * {@code .dsh-notes/p6-5-mq-freshness-probe.ps1}（66ms 可见）与
 * {@code .dsh-notes/p6-4-admin-write-probe.ps1} + {@code p6-index-content-audit.ps1}（连跑 3 轮幽灵 0）。
 *
 * <h2>为什么**不能**用 {@code @Transactional}（本模块踩过同一个坑）</h2>
 * 用例自带事务时，被测代码永远处在"未提交"状态 ⇒ {@code afterCommit} 回调**根本不会触发**，
 * 用例会变成"什么都没验"。所以本类**不加事务**，代价是自己做物理清理（见 {@link #physicalCleanup()}），
 * 保证"新库与源库逐表行数对齐"这条验收不被测试污染。
 */
class AdminIndexSyncAfterCommitMySqlTest extends ProductTestBase {

    private static final long SEED_CATEGORY_ID = 12L;   // 耳机音箱（与 AdminProductMySqlTest 同一个）
    private static final long SEED_BRAND_ID = 1L;       // 苹果

    /** 搜索域的记录器：每次被调用时，用**新连接**读一次库，把"当时库里的状态"存下来 */
    @MockitoBean
    private ProductSearchService searchStub;

    private final List<String> syncSnapshots = new ArrayList<>();
    private final List<Long> createdSpuIds = new ArrayList<>();
    private final List<Long> createdBrandIds = new ArrayList<>();

    @BeforeEach
    void clearSnapshots() {
        syncSnapshots.clear();
    }

    @AfterEach
    void physicalCleanup() {
        // 本类无事务 ⇒ 自己物理清理（pms_sku / pms_spu_detail / pms_spu / pms_brand）
        for (long spuId : createdSpuIds) {
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE spu_id = ?", spuId);
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_spu_detail WHERE spu_id = ?", spuId);
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", spuId);
        }
        createdSpuIds.clear();
        for (long brandId : createdBrandIds) {
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_brand WHERE id = ?", brandId);
        }
        createdBrandIds.clear();
    }

    // ==================================================================
    // ① 幽灵文档的正型：逻辑删除（缺陷现场）
    // ==================================================================

    @Test
    @DisplayName("[提交后同步] 逻辑删除商品：同步被调用时**新连接已能读到 deleted=1**（提交前读到的是 deleted=0）")
    void logicalDelete_syncsOnlyAfterCommit() throws Exception {
        stubSyncLater();
        long spuId = createProduct("提交后同步_删_" + suffix);
        String before = spuState(spuId);
        syncSnapshots.clear();                     // ⚠️ 建商品自己也会触发一次同步（L258），别把它算进"删除"这一步

        perform(delete("/api/admin/product/" + spuId)).andExpect(jsonPath("$.code").value(0));

        assertEquals(1, syncSnapshots.size(), "一次逻辑删除应当恰好触发一次索引同步：" + syncSnapshots);
        System.out.println("[#7 判据] 删除前(新连接): " + before);
        System.out.println("[#7 判据] 同步调用时(新连接): " + syncSnapshots.get(0));
        assertTrue(syncSnapshots.get(0).contains("deleted=1"),
                "同步被调用时，删除必须**已经提交**（新连接能读到 deleted=1）；"
                        + "若读到 deleted=0 说明同步又变回了行内调用（幽灵文档的成因）: " + syncSnapshots);
    }

    // ==================================================================
    // ② 同一条纪律的另外两条入口：上下架、品牌改名
    // ==================================================================

    @Test
    @DisplayName("[提交后同步] 上下架：同步被调用时新连接已能读到 status=1")
    void updateStatus_syncsOnlyAfterCommit() throws Exception {
        stubSyncLater();
        long spuId = createProduct("提交后同步_上架_" + suffix);
        syncSnapshots.clear();                     // 建商品那次同步不算

        perform(put("/api/admin/product/" + spuId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(1, syncSnapshots.size(), "一次上下架应当恰好触发一次索引同步");
        assertTrue(syncSnapshots.get(0).contains("status=1"),
                "同步被调用时上架必须已提交: " + syncSnapshots.get(0));
    }

    @Test
    @DisplayName("[提交后同步] 品牌改名：syncBrandLater 被调用时新连接已能读到新名字")
    void brandRename_syncsOnlyAfterCommit() throws Exception {
        doAnswer(inv -> {
            syncSnapshots.add("syncBrandLater(" + inv.getArgument(0) + ") 调用时(新连接): " + brandState(((Number) inv.getArgument(0)).longValue()));
            return null;
        }).when(searchStub).syncBrandLater(anyLong());

        String name = "提交后同步品牌_" + suffix;
        long brandId = readId(perform(post("/api/admin/brand")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sort\":9,\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
        createdBrandIds.add(brandId);

        perform(put("/api/admin/brand/" + brandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "_改\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertEquals(1, syncSnapshots.size(), "一次品牌改名应当恰好触发一次品牌级同步");
        assertTrue(syncSnapshots.get(0).contains(name + "_改"),
                "syncBrandLater 被调用时改名必须已提交（否则 search 会把旧名字写回索引）: " + syncSnapshots.get(0));
    }

    // ==================================================================
    // ③ 库存/销量链路（P6-5 #1）的"提交后"由**另一层**保证 —— 本类不重复验（附上原委）
    // ==================================================================
    //
    // 写这条注释是因为我**先写了一个错的用例**（已删）：
    //   StockCommandServiceImpl.release 是在**事务内**调 markDirty 的，而"提交后再投递"这条保证
    //   落在 mq/ProductSyncPublisher#publishSpuIds（它内部走 TxCallbacks）。本类用 @MockitoBean 换掉的是
    //   **ProductSearchService 这一层**（publisher 的上层）⇒ 桩在事务内就被调用，"提交后"的语义被绕过了。
    //   也就是说：在这个桩下面写"stub 调用时库存应已提交"必然是错的（第一次跑就红，这条记录留在这里）。
    //   ⇒ 库存/销量链路的"提交后"由两处证据守着，不在这里重复：
    //     · 单测 ProductSyncPublisherTest#publishSpuIds_defersUntilCommit（提交前不投、afterCommit 才投、回滚不投）
    //     · 活体 .dsh-notes/p6-5-mq-freshness-probe.ps1（真 8102+broker+ES：变更→索引可见 66ms）
    // 另一个删除它的理由：这条链路要用共享的 seed SKU 2001，而**另一个施工方同时在跑会写真库的测试套件**时
    //   库存会被交错改动（本批实测过一次丢更新）⇒ 这类"拿共享行当断言基准"的用例不稳定。

    // ==================================================================
    // 桩 + 新连接读
    // ==================================================================

    private void stubSyncLater() {
        doAnswer(inv -> {
            long spuId = ((Number) inv.getArgument(0)).longValue();
            syncSnapshots.add("syncLater(" + spuId + ") 调用时(新连接): " + spuState(spuId));
            return null;
        }).when(searchStub).syncLater(anyLong());
    }

    private String spuState(long spuId) {
        return queryOne("SELECT CONCAT('deleted=', deleted, ' status=', status) FROM "
                + EXPECTED_SCHEMA + ".pms_spu WHERE id = " + spuId);
    }

    private String brandState(long brandId) {
        return queryOne("SELECT CONCAT('name=', name) FROM " + EXPECTED_SCHEMA + ".pms_brand WHERE id = " + brandId);
    }

    /**
     * 用**一条新的物理连接**读（不是事务里那条、也不是 JdbcTemplate 复用的事务绑定连接）：
     * 新连接的第一次读只能看到**已提交**的版本 ⇒ 这就是"提交时刻"的探针。
     */
    private String queryOne(String sql) {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getString(1) : "<新连接里还没有这一行>";
        } catch (Exception e) {
            return "<读取失败: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
        }
    }

    // ==================================================================
    // 造数据
    // ==================================================================

    private long createProduct(String title) throws Exception {
        long spuId = readId(perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonKit.toJson(productBody(title))))
                .andExpect(jsonPath("$.code").value(0)).andReturn());
        createdSpuIds.add(spuId);
        return spuId;
    }

    private Map<String, Object> productBody(String title) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("categoryId", SEED_CATEGORY_ID);
        product.put("brandId", SEED_BRAND_ID);
        product.put("title", title);
        product.put("subtitle", "提交后同步判据");
        product.put("mainImage", "http://img/test/aftercommit.jpg");
        product.put("description", "自动化测试商品");
        product.put("detailHtml", "<p>after-commit</p>");
        product.put("images", List.of("http://img/test/1.jpg"));
        product.put("params", List.of(Map.of("name", "材质", "value", "纯棉")));
        product.put("skus", List.of(Map.of(
                "skuCode", "AC-" + suffix, "price", 19900L, "stock", 25)));
        return product;
    }

    private static long readId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.data");
        return id.longValue();
    }
}
