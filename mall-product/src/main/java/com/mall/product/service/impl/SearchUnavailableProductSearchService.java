package com.mall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.product.domain.Spu;
import com.mall.product.dto.ProductIdPage;
import com.mall.product.dto.ProductSearchDoc;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.service.ProductSearchService;
import com.mall.product.support.CacheKeys;
import com.mall.common.support.CacheService;
import com.mall.product.support.constant.EnableStatus;
import com.mall.product.support.dto.ReindexResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

/**
 * <b>"索引不可用"的降级体</b>（P6-1 建成；P6-3 起**不再是** {@code ProductSearchService} 的候选实现）。
 *
 * <h2>⓪ P6-3 起的角色变化（先看这条，别按旧注释理解）</h2>
 * 本类**去掉了 {@code @Service}**：它现在由 {@link RemoteProductSearchService} **内部持有并复用**
 * （构造函数注入 {@code CacheService}/{@code SpuMapper} 后 {@code new} 出来）。
 * 这样全上下文里 {@link ProductSearchService} 只有**一个** bean（远程实现），
 * 不存在"两个实现谁能赢"的模糊态，也不会因为组件扫描顺序不同而行为漂移
 * —— 这正是原来类注释 ⑧ 担心的那件事，现在用"根本不是 bean"从根上消除了。
 * <p>本类的方法语义**一个字都没改**（下面 ②③④⑤⑥ 仍然逐条有效），改的只是"谁持有它"。
 * 守卫用例 {@code SearchUnavailableProductSearchServiceTest} 保留并已更新：
 * 接线守卫改成断言"上下文里拿到的是远程实现"，而本类的降级语义仍被逐方法钉死（直接 {@code new} 出来验）。
 *
 * <h2>① P6-1 期为什么必须有这个类</h2>
 * {@code ProductPortalServiceImpl} 注入 {@link ProductSearchService}（前台货架的关键字分支），
 * 而 P6-1 **不搬 ES**（`service/impl/ProductSearchServiceImpl` 依赖 Elasticsearch 客户端与 RabbitMQ，
 * 属 P6-2 的 {@code mall-search}）。少一个实现 ⇒ 上下文启动就失败 ⇒ 交付证据第 4 条
 * （Nacos 发现 + 8102 起来）根本拿不到。所以这里给一个**显式的降级实现**，
 * 让"检索不可用"成为一个**被写下来的状态**，而不是一个启动异常。
 *
 * <h2>② 语义：**索引不可用**，不是"没有数据"</h2>
 * 这两者在对外表现上必须能区分，否则会出现最坏的一种降级：
 * "ES 挂了"看起来像"这个商品不存在" —— 前台货架静默变空，而且没人报警。本类的每个方法都按
 * 这个语义取值，**逐条照抄单体 {@code ProductSearchServiceImpl} 在 ES 不可用时的现状行为**：
 * <pre>
 * 方法              单体 ES 不可用时的现状                        本实现（照抄）
 * search            throw IllegalStateException（调用方 catch → MySQL 回落）  同（见下）
 * count()           -1（索引不存在 / 读失败）                     -1
 * pendingCount()    Redis 不可用时 -1                             -1（本类语义：索引链路不可用）
 * titleAnalyzer()   分词器探测链**全部失败**后的兜底值 "standard"  "standard"（照抄，别自创）
 * findById()        catch → null                                  null
 * searchByTitle()   catch → List.of()                             List.of()
 * syncProduct()     catch → log.warn + **return false**（不抛，不阻塞商品写） return false
 * deleteProduct()   catch → log.warn（void）                      空实现 + debug 日志
 * syncByBrand()     逐个 syncProduct 全失败 ⇒ 返回 0               0
 * reindex()         单体将 BusinessException(500) 抛出            见下（本类不抛，指令要求）
 * </pre>
 *
 * <h2>③ {@link #search} 为什么**抛**而不是返回空结果（这是本类最关键的一处）</h2>
 * 调用方 {@code ProductPortalServiceImpl#searchByEs} 的结构是"**抛异常 → 回落 MySQL LIKE**"：
 * <pre>
 *   try { ProductIdPage p = productSearchService.search(...); ... return 结果; }
 *   catch (Exception e) { log.warn("ES 检索失败，降级为 MySQL LIKE：..."); return null; }   // null ⇒ 走下面的 MySQL 分页
 * </pre>
 * 也就是说：**只有异常才会触发降级分支**。若这里返回 `total=0` 的空 {@code PageResult}，
 * 调用方会把它当成"关键字确实没命中"直接返回给前端 —— 前台按关键字搜任何东西都是 0 条，
 * 而 MySQL 里其实有数据。那正是"索引不可用被伪装成没有数据"。
 * 单体的 ES 真挂掉时走的也正是这条 catch → 回落路径（`ProductSearchServiceImpl.search` 的 catch 里
 * `throw new IllegalStateException("Elasticsearch 检索失败: ...")`），所以**抛才是"照现状"**。
 *
 * <h2>④ 绝不打断业务</h2>
 * 除 {@code search}（它的调用方**本来就**按异常设计）之外，其余方法一律**不抛异常**：
 * 库存/销量变更后的 {@code markDirty}、后台写商品后的 {@code syncLater} 都在业务事务里被调用，
 * 它们抛异常 = "索引不可用导致下单失败"，这与现状口径（"ES 不可用不影响商品读/写"）相反。
 *
 * <h2>⑤ {@code markDirty} 为什么**保留**（而不是彻底空实现）</h2>
 * 规格 §4.5 明确允许"按编译需要留一个**待同步标记**的本地实现"，而"变更后按 SPU 标记索引待同步"
 * 是 5 条库存现状细节的第 5 条（P6-plan §二）。所以这里保留**调用点与落点**：
 * 写 Redis 集合 {@code mall:es:pending}（{@link CacheKeys#esPendingSync()}，**键名与单体逐字相同**）。
 * 关键一点：那个集合**正是单体 {@code ProductSearchSyncTask} 当前正在消费的同一个 key**，
 * 所以本服务写进去的标记**不会丢** —— 只是"谁来消费"暂时是单体的定时任务。
 * 写入是幂等 SADD 且 fail-open（Redis 不可用只告警），不会抛异常。
 * <p>⚠️ 与之配套：{@link #drainPending(int)} **刻意返回空列表、不去 SPOP**：
 * 在 P6-1 里真正的消费者是单体的定时任务，本服务若"顺手取走"就等于**从别人的队列里偷任务**。
 *
 * <h2>⑥ 与单体的唯一语义差（必须上报，不能带着它切流量）</h2>
 * <ul>
 *   <li>{@link #reindex()}：单体在 ES 不可用时抛 {@code BusinessException(500,"商品索引重建失败：…")}；
 *       本类按"绝不打断业务"的要求改为**空实现 + debug 日志 + 返回 {@code null}**。
 *       本服务 P6-1 **没有任何调用方**（ES 运维端点 `AdminEsController` 没有搬过来），故对外无差异。
 *       P6-2 必须由 {@code mall-search} 提供真的 {@code reindex}。</li>
 *   <li>{@link #syncLater} / {@link #syncBrandLater}：单体是"MQ 可用则异步写 ES，否则**同步**写 ES"
 *       （写失败返回 false）；本类是"**只标记**待同步"。P6-1 不切流量 ⇒ 对外不可见；
 *       **P6-4 切路由前必须消除这个差异**（要么 product 能真的写索引，要么由 search 侧消费标记）。</li>
 * </ul>
 *
 * <h2>⑦ P6-2 的替换点（照这个清单换，别漏）</h2>
 * <ol>
 *   <li>删除本类，改由通过服务发现调 {@code lb://mall-search} 的远程实现提供
 *       {@link ProductSearchService}（与 P5 的 {@code ProductClient}/远程契约同一套写法：
 *       `common/client` 风格的出站客户端 + `LoadBalancedClientConfig`）；</li>
 *   <li>⚠️ 那时 `LoadBalancedClientConfig` 与 `spring-cloud-starter-loadbalancer` 才需要加进 pom
 *       （P6-1 刻意没加：本批零出站调用）；</li>
 *   <li>{@code ProductPortalServiceImpl} / {@code AdminProductServiceImpl} / {@code StockCommandServiceImpl}
 *       **一行都不用改**（它们只认接口）——这正是本类存在的意义；</li>
 *   <li>本类的守卫用例 {@code SearchUnavailableProductSearchServiceTest} 必须**一起改**：
 *       它断言"上下文里的实现就是本类"，换实现时会立刻变红，
 *       逼替换者回来把"降级"改成真正的"远程 + 降级"（P6-plan §三 第 3 条的三种回落判定：
 *       不可达 / 超时 / 非 0 业务码 —— 三种都回落，且**只记日志**，不能把 500 抛给用户）；</li>
 *   <li>`search` 的异常信号在 P6-2 会变成"远程调用的失败/超时"，**回落分支的代码不用动**
 *       （`ProductPortalServiceImpl#searchByEs` 的 catch 已经覆盖），但要新增
 *       "返回非 0 业务码也算失败"这一条判定（现在这版没有业务码可判）。</li>
 * </ol>
 *
 * <h2>⑧ 为什么不再是 {@code @Service}（P6-3 的处置）</h2>
 * P6-1 期本类是**唯一**实现；P6-3 之后唯一的实现是 {@link RemoteProductSearchService}，
 * 本类退化为它内部的降级体。曾经的备选方案是给本类加 {@code @ConditionalOnMissingBean}，
 * 但那个注解只在**自动配置**阶段被可靠评估，放在被组件扫描发现的普通 {@code @Service} 上时
 * 与其它 bean 定义的注册顺序**没有保证** ⇒ 可能"两个都装配（注入歧义）"或"谁都没装配（启动失败）"。
 * 现在的做法（本类不是 bean、由远程实现持有）把这件事变成**结构性确定**的，
 * "当前接的是哪个实现"再用**可执行守卫**钉一次（见 ⑦ 第 4 条）。
 */
@Slf4j
@RequiredArgsConstructor
public class SearchUnavailableProductSearchService implements ProductSearchService {

    /** Redis 待同步集合（键名与单体 / P6-2 的 mall-search 必须逐字相同） */
    private final CacheService cacheService;

    private final SpuMapper spuMapper;

    /** 统一的"为什么是降级态"说明，出现在每条 debug 日志里，便于排查"索引怎么没更新" */
    private static final String DEGRADED = "索引不可用(P6-1：ES 属 P6-2 的 mall-search)，本次调用按降级处理";

    // ==================================================================
    // 检索（唯一会"抛"的方法，见类注释 ③）
    // ==================================================================

    @Override
    public ProductIdPage search(String keyword, List<Long> categoryIds, Long brandId,
                                Long minPrice, Long maxPrice, String sort,
                                long pageNum, long pageSize) {
        // ⚠️ 刻意抛异常（与单体 ES 失败时同一形状）：调用方 ProductPortalServiceImpl#searchByEs
        //    只有 catch 到异常才会回落 MySQL LIKE。返回空结果 = 把"索引不可用"伪装成"没有数据"。
        throw new IllegalStateException(
                "商品索引不可用：" + DEGRADED + "；调用方应回落 MySQL LIKE 路径");
    }

    /** 关键字检索（单体里仅测试用）：照抄 ES 不可用时的 null 语义 → 空列表 */
    @Override
    public List<ProductSearchDoc> searchByTitle(String keyword, int size) {
        log.debug("searchByTitle 降级为空结果: keyword={} size={} ({})", keyword, size, DEGRADED);
        return List.of();
    }

    /** 按 id 取索引文档：照抄 ES 不可用时的 null 语义（"读不到"而不是"文档为空"） */
    @Override
    public ProductSearchDoc findById(long spuId) {
        log.debug("findById 降级为 null: spuId={} ({})", spuId, DEGRADED);
        return null;
    }

    // ==================================================================
    // 状态自检（-1 / 兜底值都是**照抄现状**，不是自创）
    // ==================================================================

    /** -1 = 索引不可用（照抄单体：索引不存在 / 读取失败都返回 -1）。⚠️ 不是 0：0 是"索引存在但没有文档" */
    @Override
    public long count() {
        return -1L;
    }

    /**
     * -1 = 待同步链路不可用（与 {@link #count()} 同一口径）。
     *
     * <p>⚠️ 注意与 {@link #markDirty} 的关系：{@code markDirty} 仍会把 spuId 写进 Redis 兜底集合，
     * 但本方法按"索引链路不可用"的语义固定返回 -1，**不**去读那个集合的大小——
     * 因为 P6-1 里那个集合的真正消费者是**单体的** {@code ProductSearchSyncTask}，
     * 这里报它的长度只会让运维误以为"本服务在管这条队列"。
     */
    @Override
    public long pendingCount() {
        return -1L;
    }

    /**
     * 分词器：**照抄单体在 ES 不可用时取到的值 {@code "standard"}**。
     *
     * <p>为什么是这个值：单体的 {@code resolveTitleAnalyzer()} 按
     * {@code smartcn → cjk → standard} 顺序探测可用性，**探测全部失败**时最后兜底
     * {@code resolvedTitleAnalyzer = "standard"} 并返回 —— 也就是说 ES 完全连不上时，
     * 单体对外报的就是 {@code "standard"}。本类照抄是为了"报出来的值与现状一致"。
     * <p>⚠️ 但必须记住它的真实含义是"**探测链全部失败后的兜底值**"，不是"分词器可用"：
     * P6-2 恢复检索能力时，这个字段要能区分"真的用了 standard"与"根本没探到"。
     */
    @Override
    public String titleAnalyzer() {
        return "standard";
    }

    // ==================================================================
    // 写索引：一律安全空实现 / 只标记（绝不抛异常打断业务，见类注释 ④）
    // ==================================================================

    /**
     * 全量重建：本服务没有索引可重建 ⇒ 空实现。
     *
     * <p>⚠️ 与单体的语义差：单体在 ES 不可用时会抛 {@code BusinessException(500,"商品索引重建失败：…")}；
     * 本类按"绝不打断业务"改为 debug 日志 + 返回 {@code null}（= **没有执行**，
     * 而不是"重建了 0 篇"——后者的形状会被误读成"索引是空的"）。P6-1 无调用方，见类注释 ⑥。
     */
    @Override
    public ReindexResult reindex() {
        log.debug("reindex 跳过（无索引可重建）: {}", DEGRADED);
        return null;
    }

    /** 单条同步：照抄单体失败路径 —— 返回 false、不抛（"索引同步失败不阻塞商品写操作"） */
    @Override
    public boolean syncProduct(long spuId) {
        log.debug("syncProduct 未执行: spuId={} ({})", spuId, DEGRADED);
        return false;
    }

    /** 从索引删除：照抄单体失败路径 —— 只记日志（void） */
    @Override
    public void deleteProduct(long spuId) {
        log.debug("deleteProduct 未执行: spuId={} ({})", spuId, DEGRADED);
    }

    /** 品牌维度同步：单体在索引不可用时逐条失败 ⇒ 返回 0（成功同步 0 篇） */
    @Override
    public int syncByBrand(long brandId) {
        log.debug("syncByBrand 未执行: brandId={} ({})", brandId, DEGRADED);
        return 0;
    }

    /** 库存/销量变更后的"待同步标记"：写 Redis 兜底通道（spec §4.5 明许的本地标记实现） */
    @Override
    public void markDirty(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return;
        }
        List<Long> ids = spuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        markPending(ids, "库存/销量变更");
    }

    /** 后台写商品后"尽快同步"：本类只标记（单体在 MQ 关闭时是同步写 ES —— 见类注释 ⑥） */
    @Override
    public void syncLater(long spuId) {
        markPending(List.of(spuId), "后台写商品");
    }

    /** 品牌改名后同步整个品牌：只标记该品牌**在架**商品（口径与单体 syncByBrand 一致） */
    @Override
    public void syncBrandLater(long brandId) {
        List<Long> spuIds = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                        .eq(Spu::getBrandId, brandId)
                        .eq(Spu::getStatus, EnableStatus.ENABLED))
                .stream().map(Spu::getId).toList();
        if (spuIds.isEmpty()) {
            return;
        }
        markPending(spuIds, "品牌改名");
    }

    /**
     * 取出待同步商品：**刻意返回空、不去 SPOP**。
     *
     * <p>P6-1 里 {@code mall:es:pending} 的真正消费者是**单体的** {@code ProductSearchSyncTask}；
     * 本服务若在这里取走成员，等于把单体的同步任务偷走（那些商品就永远不会被同步到索引，
     * 而且没有任何日志）。P6-2 由 {@code mall-search} 提供真的队列消费（单体/mark-search 二选一，不能两个都消费）。
     */
    @Override
    public List<Long> drainPending(int max) {
        return List.of();
    }

    // ==================================================================
    // private
    // ==================================================================

    /** 写兜底通道：SADD 到 {@code mall:es:pending}（幂等；Redis 不可用只告警，绝不抛） */
    private void markPending(List<Long> spuIds, String reason) {
        String[] members = spuIds.stream().map(String::valueOf).toArray(String[]::new);
        long added = cacheService.setAdd(CacheKeys.esPendingSync(), members);
        if (added < 0) {
            log.warn("索引增量同步队列不可用(Redis 不可用)，{} 个商品待全量重建筑底: reason={}", members.length, reason);
            return;
        }
        log.debug("已标记 {} 个商品待同步索引(Redis 兜底通道，新增 {}): reason={} ({})",
                members.length, added, reason, DEGRADED);
    }
}
