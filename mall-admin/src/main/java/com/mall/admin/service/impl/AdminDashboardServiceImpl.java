package com.mall.admin.service.impl;

import com.mall.admin.client.ProductStatClient;
import com.mall.admin.client.TradeStatClient;
import com.mall.admin.client.UserCenterMemberClient;
import com.mall.admin.config.DashboardExecutorConfig;
import com.mall.admin.dto.DashboardVO;
import com.mall.admin.service.AdminDashboardService;
import com.mall.admin.support.DashboardCache;
import com.mall.admin.support.dto.OrderSummaryVO;
import com.mall.admin.support.dto.OrderTrendPointVO;
import com.mall.admin.support.dto.SpuAmountVO;
import com.mall.admin.support.dto.SpuSnapshotVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * 看板统计：向三个域各取"自己那部分"，在 BFF 侧组装成看板视图（P7 §3 的核心类）。
 *
 * <h2>与单体 {@code AdminDashboardServiceImpl} 的关系（C1）</h2>
 * <b>对外表现逐字相同</b>（路径 / 参数 / 响应字段 / code / 文案 / HTTP 恒 200），
 * 差别只在"数据怎么来"：
 * <pre>
 *   单体：同进程调 4 个域服务接口（OrderStatQueryService / ProductStatQueryService / ProductQueryService / MemberQueryService）
 *   本类：并行调 3 个服务的内部契约（trade=mall-legacy / product / user-center），各自失败时降级
 * </pre>
 * 组装逻辑（谁提供哪个字段、销售额榜"金额来自交易域、标题来自商品域"的分工、排序位置）
 * 与单体<b>一一对应</b>，因为那些都是对外口径而不是实现细节。
 *
 * <h2>① 并行：为什么是"先全部 submit、再逐个 join"</h2>
 * 这不是风格问题，而是这个类最容易写错的地方：
 * <pre>
 *   ✗ 错：T a = fetch("trade", ...); T b = fetch("product", ...);
 *          —— 每个 fetch 内部 join，于是第二个任务在第一个**完成之后**才提交 ⇒ 串行，总耗时是三者之和
 *   ✓ 对：全部 submit（立刻拿到 3 个 future）→ 再 join
 * </pre>
 * 判据也是可执行的（不是"看起来在并行"）：用例用三个会 sleep 的环回桩量
 * <b>请求到达的重叠度</b>（峰值并发 == 3）与<b>墙钟时间</b>（≈ 最慢的一个，而不是三者之和）。
 *
 * <h2>② 降级：本批的核心验收</h2>
 * 每个下游调用都套一层"捕获 → 记一笔 → 返回兜底值"：
 * <ul>
 *   <li>summary：交易域 4 个字段 → 0；商品域 → 0；会员域 → 0（**各域独立降级**，
 *       挂了交易域不影响"在架商品数"仍是真的）；</li>
 *   <li>trend：序列只有交易域一个来源 ⇒ 不可用就是**空数组**；</li>
 *   <li>top：列表的来源是"行来源"域（sales→商品域；amount→交易域），
 *       而 amount 还需要商品域补标题 ⇒ 两者任一不可用都是**空数组**
 *       （理由：宁可不给榜单，也不要把"商品服务挂了"显示成"商品已删除"——
 *        那是一个**看起来正常**的错误结论）；</li>
 *   <li>每次都只留**一条** {@code log.warn}（把本次所有降级依赖收在一行里）——
 *       "每请求一条"是可断言的，而"每个依赖一条"会让日志条数随故障数变化。</li>
 * </ul>
 *
 * <h2>③ 缓存：短 TTL + 主动失效，且<b>降级结果不入缓存</b></h2>
 * 写入前检查"本次是否发生过降级"：把"trade 挂了 ⇒ 4 个字段全 0"缓存 60 秒，
 * 等于让一次瞬时故障变成一个分钟的对外错误结论，而且形状完全正常、**没有任何迹象**。
 * 失效由 {@link DashboardCache#evictAll()}（换代）承担，调用点在会员启停这条写路径上（提交后）。
 *
 * <h2>为什么不需要给 future 加 orTimeout</h2>
 * 每个出站客户端都设了连接 300ms / 读 2500ms（{@code mall.*.read-timeout-ms}）——
 * 那就是"各自设短超时"的落点。再加一层 future 超时只会引入第二个上限，
 * 两者不一致时反而更难解释（"到底是谁超的"）。
 */
@Slf4j
@Service
public class AdminDashboardServiceImpl implements AdminDashboardService {

    /** 降级日志里用的域名（与用例断言的字面量一致） */
    static final String DOMAIN_TRADE = "trade";
    static final String DOMAIN_PRODUCT = "product";
    static final String DOMAIN_USER = "user";

    /** "销售额榜"的 type 字面量（与单体 {@code AdminDashboardServiceImpl.top} 同一判据） */
    private static final String TYPE_AMOUNT = "amount";

    private final TradeStatClient tradeStatClient;
    private final ProductStatClient productStatClient;
    private final UserCenterMemberClient userCenterMemberClient;
    private final DashboardCache dashboardCache;
    private final ExecutorService executor;

    public AdminDashboardServiceImpl(TradeStatClient tradeStatClient,
                                     ProductStatClient productStatClient,
                                     UserCenterMemberClient userCenterMemberClient,
                                     DashboardCache dashboardCache,
                                     @Qualifier(DashboardExecutorConfig.DASHBOARD_EXECUTOR) ExecutorService executor) {
        this.tradeStatClient = tradeStatClient;
        this.productStatClient = productStatClient;
        this.userCenterMemberClient = userCenterMemberClient;
        this.dashboardCache = dashboardCache;
        this.executor = executor;
    }

    // ==================================================================
    // GET /api/admin/dashboard/summary
    // ==================================================================

    @Override
    public DashboardVO.Summary summary() {
        String key = dashboardCache.key("summary");
        DashboardVO.Summary cached = dashboardCache.get(key, DashboardVO.Summary.class);
        if (cached != null) {
            return cached;
        }

        Degradation degraded = new Degradation();
        // 🔴 三个 submit **必须**都先发出去（见类注释 ①）——写成 `join(submit(...))` 三行就是串行
        CompletableFuture<OrderSummaryVO> tradeFuture = submit(DOMAIN_TRADE, degraded, tradeStatClient::summary);
        CompletableFuture<Long> productFuture = submit(DOMAIN_PRODUCT, degraded, productStatClient::enabledCount);
        CompletableFuture<Long> userFuture = submit(DOMAIN_USER, degraded, userCenterMemberClient::count);

        OrderSummaryVO order = tradeFuture.join();
        Long onShelfProductCount = productFuture.join();
        Long memberCount = userFuture.join();

        DashboardVO.Summary vo = new DashboardVO.Summary();
        // 交易域不可用 ⇒ 这四项为 0（**不是 null**：单体里它们是原始 long，永远是数字）
        vo.setTodayOrderCount(order == null ? 0L : order.getTodayOrderCount());
        vo.setTodaySalesAmount(order == null ? 0L : order.getTodaySalesAmount());
        vo.setWaitShipCount(order == null ? 0L : order.getWaitShipCount());
        vo.setRefundPendingCount(order == null ? 0L : order.getRefundPendingCount());
        vo.setOnShelfProductCount(onShelfProductCount == null ? 0L : onShelfProductCount);
        vo.setMemberCount(memberCount == null ? 0L : memberCount);

        cacheIfHealthy(key, vo, degraded, "summary");
        return vo;
    }

    // ==================================================================
    // GET /api/admin/dashboard/trend
    // ==================================================================

    @Override
    public List<DashboardVO.TrendItem> trend(int days) {
        String key = dashboardCache.key("trend:" + days);
        List<DashboardVO.TrendItem> cached = dashboardCache.get(key, trendType());
        if (cached != null) {
            return cached;
        }

        Degradation degraded = new Degradation();
        // 只有一个来源（交易域）；仍走同一条 submit/join 路径 —— 让"降级语义"只有一份实现
        List<OrderTrendPointVO> points = submit(DOMAIN_TRADE, degraded, () -> tradeStatClient.trend(days)).join();

        List<DashboardVO.TrendItem> list = new ArrayList<>();
        if (points != null) {
            for (OrderTrendPointVO point : points) {
                DashboardVO.TrendItem item = new DashboardVO.TrendItem();
                item.setDate(point.getDate());
                item.setOrderCount(point.getOrderCount());
                item.setSalesAmount(point.getSalesAmount());
                list.add(item);
            }
        }

        cacheIfHealthy(key, list, degraded, "trend");
        return list;
    }

    // ==================================================================
    // GET /api/admin/dashboard/top
    // ==================================================================

    @Override
    public List<DashboardVO.TopItem> top(String type, int limit) {
        // type 判据与单体逐字一致（大小写不敏感、非 amount 一律按 sales）
        boolean byAmount = TYPE_AMOUNT.equalsIgnoreCase(type);
        String normalized = byAmount ? TYPE_AMOUNT : "sales";
        String key = dashboardCache.key("top:" + normalized + ":" + limit);
        List<DashboardVO.TopItem> cached = dashboardCache.get(key, topType());
        if (cached != null) {
            return cached;
        }

        Degradation degraded = new Degradation();
        List<DashboardVO.TopItem> list = byAmount ? topByAmount(limit, degraded) : topBySales(limit, degraded);

        cacheIfHealthy(key, list, degraded, "top");
        return list;
    }

    /** 销量榜：商品域已经按销量倒序取好前 N（与单体 {@code topBySales} 同分工） */
    private List<DashboardVO.TopItem> topBySales(int limit, Degradation degraded) {
        List<SpuSnapshotVO> rows = submit(DOMAIN_PRODUCT, degraded, () -> productStatClient.topBySales(limit)).join();
        if (rows == null) {
            // 商品域不可用 ⇒ 空数组（榜单的行来源就是它）
            return List.of();
        }
        List<DashboardVO.TopItem> list = new ArrayList<>();
        for (SpuSnapshotVO spu : rows) {
            DashboardVO.TopItem item = new DashboardVO.TopItem();
            item.setSpuId(spu.getId());
            item.setTitle(spu.getTitle());
            item.setMainImage(spu.getMainImage());
            // 单体：sales 为 null 时按 0（原始 long，不能是 null）
            item.setValue(spu.getSales() == null ? 0L : spu.getSales());
            list.add(item);
        }
        return list;
    }

    /** 销售额榜：交易域给"spuId + 金额"的有序列表，商品域补标题/主图（与单体 {@code topByAmount} 同分工） */
    private List<DashboardVO.TopItem> topByAmount(int limit, Degradation degraded) {
        List<SpuAmountVO> rows = submit(DOMAIN_TRADE, degraded, () -> tradeStatClient.topPaidAmountBySpu(limit)).join();
        if (rows == null || rows.isEmpty()) {
            // 交易域不可用 ⇒ 空数组；交易域**正常但确实没有已支付订单** ⇒ 也是空数组（单体同）
            return List.of();
        }
        List<Long> spuIds = rows.stream().map(SpuAmountVO::getSpuId).filter(java.util.Objects::nonNull).toList();
        Map<Long, SpuSnapshotVO> spuMap = submit(DOMAIN_PRODUCT, degraded, () -> productStatClient.spus(spuIds)).join();
        if (spuMap == null) {
            // 商品域不可用 ⇒ 空数组（见类注释 ②：不把"商品服务挂了"说成"商品已删除"）
            return List.of();
        }
        List<DashboardVO.TopItem> list = new ArrayList<>();
        for (SpuAmountVO row : rows) {
            SpuSnapshotVO spu = spuMap.get(row.getSpuId());
            DashboardVO.TopItem item = new DashboardVO.TopItem();
            item.setSpuId(row.getSpuId());
            // ⚠️ 这里与降级无关：**调用成功但查不到该 SPU** 就是单体口径的"商品已删除"
            //    （mainImage 为空是业务值，不是降级占位）
            item.setTitle(spu == null ? "商品已删除" : spu.getTitle());
            item.setMainImage(spu == null ? null : spu.getMainImage());
            item.setValue(row.getAmount());
            list.add(item);
        }
        return list;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 提交一次下游取数：成功返回结果，失败记一笔降级并返回 {@code null}（由调用方给兜底值）。
     *
     * <p>捕获的是 {@link RuntimeException}：客户端已经把传输异常/非 0 业务码统一成
     * {@code BusinessException}（见三个 client 的 {@code unwrap}），所以这里不需要再分类型——
     * 降级策略对"连不上"与"下游报错"是同一个（都是 0/空 + warn）。
     */
    private <T> CompletableFuture<T> submit(String domain, Degradation degraded, Supplier<T> call) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return call.get();
                } catch (RuntimeException e) {
                    degraded.record(domain, e);
                    return null;
                }
            }, executor);
        } catch (RuntimeException e) {
            // 线程池已关闭/队列拒绝（容器正在停机）：仍然不许 500——按"该域不可用"降级
            degraded.record(domain, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 只在本次**没有发生任何降级**时写缓存（见类注释 ③） */
    private void cacheIfHealthy(String key, Object value, Degradation degraded, String endpoint) {
        if (degraded.any()) {
            degraded.logWarn(endpoint);
            return;
        }
        dashboardCache.put(key, value);
    }

    private static TypeReference<List<DashboardVO.TrendItem>> trendType() {
        // 显式写出泛型（照抄 mall-product ProductPortalServiceImpl 的写法）：
        // 泛型擦除下 Jackson 只能反序列化成 List<LinkedHashMap>，随后 ClassCastException（编译期看不出来）
        return new TypeReference<List<DashboardVO.TrendItem>>() {
        };
    }

    private static TypeReference<List<DashboardVO.TopItem>> topType() {
        return new TypeReference<List<DashboardVO.TopItem>>() {
        };
    }

    /**
     * 一次请求内的降级账本：哪些域挂了、原因是什么。
     *
     * <p>线程安全（三个取数并发跑）。原因取 {@code getMessage()} 而不是完整异常栈：
     * 这一行日志要能"一眼看完"，完整栈在客户端已经 {@code debug} 掉了
     * （真要看栈时把 {@code com.mall.admin.client} 调到 debug 即可）。
     */
    static final class Degradation {

        private final Map<String, String> failures = new ConcurrentHashMap<>();

        void record(String domain, RuntimeException cause) {
            String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            failures.putIfAbsent(domain, reason);
        }

        boolean any() {
            return !failures.isEmpty();
        }

        /**
         * 每请求**恰好一条** WARN（用例 {@code AdminDashboardConcurrencyStubTest} 钉住条数）：
         * 把三个域的结果合成一行输出，否则日志条数会随故障数变化，
         * 而"本次到底降级了几个域"正是排障时要看的第一件事。
         */
        void logWarn(String endpoint) {
            // TreeMap：输出顺序稳定（trade < product < user 按字典序），便于日志检索与用例断言
            log.warn("看板降级: endpoint={} 不可用的依赖={} ⇒ 缺的那部分按 0/空数组返回"
                            + "（HTTP 200 / code=0 / 键路径集合不变；本次结果不入缓存）",
                    endpoint, new TreeMap<>(failures));
        }
    }
}
