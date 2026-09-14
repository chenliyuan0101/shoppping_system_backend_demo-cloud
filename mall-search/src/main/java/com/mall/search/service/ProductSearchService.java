package com.mall.search.service;

import com.mall.search.support.dto.ReindexResult;
import com.mall.search.dto.EsClusterInfo;
import com.mall.search.dto.ProductIdPage;
import com.mall.search.dto.ProductSearchDoc;

import java.util.Collection;
import java.util.List;

/**
 * 商品检索(Elasticsearch)。
 *
 * <p>分工：**ES 负责关键字 + 全部筛选 + 排序 + 分页 + 总数**（含类目含子类、价格区间语义与
 * 原 MySQL 货架 SQL 对齐）；MySQL 只在拿到 spuId 列表后回表装配展示字段。
 * ES 不可用时由调用方(ProductPortalService)回落原有 MySQL LIKE 路径。
 */
public interface ProductSearchService {

    /** 索引名 */
    String INDEX = "mall_product";

    /**
     * 全量重建商品索引：删旧索引 → 建索引(映射) → 批量写入在架商品 → refresh。
     * 幂等：可反复执行；重建期间检索不可用(演示规模下为秒级)。
     */
    ReindexResult reindex();

    /**
     * 按条件检索：返回命中的 spuId(已排序) + 命中总数。
     *
     * @param categoryIds 类目范围(已由 CategoryScopeResolver 展开为"含子类"的 id 列表，null/空=不限)
     * @param sort        default|sales|priceAsc|priceDesc|newest(与前台货架一致)
     */
    ProductIdPage search(String keyword, List<Long> categoryIds, Long brandId,
                         Long minPrice, Long maxPrice, String sort,
                         long pageNum, long pageSize);

    /** 商品写操作后的单条同步：在架则写/覆盖文档，非在架(下架/删除)则从索引删除；返回是否成功 */
    boolean syncProduct(long spuId);

    /**
     * <b>批量同步若干 spuId</b>（批量通道的**唯一**入口：MQ 消费者的多 id 消息、Redis 兜底 drain 的一批）。
     *
     * <p>与"循环调 {@link #syncProduct} 每个 id 一次"的区别**只在代价、不在语义**：
     * 逐条是 N 次取内容 + N 次写 + **N 次 refresh**（每篇都要等一次 ES 刷新，P6-5 #3 实测 45 篇 ≈60.9s）；
     * 本方法是一次取内容 + 一次 bulk + **整批恰好一次 refresh**（45 篇 ≈0.3~0.9s）。
     *
     * <p>语义与逐条循环**逐条对齐**（这是本方法能被消费者/定时任务直接替换掉循环的前提）：
     * <ul>
     *   <li>内容取到（在架）⇒ 写入索引；内容取不到（下架/删除/不存在）⇒ 从索引删除，且**算成功**
     *       （与 {@code syncProduct} 的"下架也算同步成功"一致；ES 的 delete 本身幂等）；</li>
     *   <li>内容源失败、ES 不可达、refresh 失败 ⇒ 受影响的 id **全部**算失败（宁可重试一次幂等操作，
     *       也不能把"没写成功/不保证可见"报成成功）；</li>
     *   <li>返回值是**失败的 spuId 列表**，按**入参顺序**给出，**保留重复**、**保留 null**
     *       （原实现是 {@code if (spuId == null || !syncProduct(spuId)) failed.add(spuId)} ——
     *       null 入参本来就是一条"失败"，调用方随后会把它过滤掉不再重投；这里逐字保持，
     *       否则消费侧的日志与重试行为会漂）。</li>
     * </ul>
     *
     * @param spuIds 待同步的 spuId（可空、可含 null、可重复）；null/空入参 ⇒ 返回空列表（一次 ES 都不碰）
     * @return **失败**的 spuId 列表（空列表 = 全成功）
     */
    List<Long> syncProducts(Collection<Long> spuIds);

    /** 从索引删除某商品(删除商品时调用) */
    void deleteProduct(long spuId);

    /** 把某品牌下所有在架商品重写入索引(品牌改名后调用) */
    int syncByBrand(long brandId);

    /**
     * 把 spuId **标记进 Redis 待同步集合**（{@code mall:es:pending}），返回本次**新增**的成员数。
     *
     * <p><b>P6-5 #5 的新增能力</b>：这是 {@code POST /internal/v1/search/mark-dirty} 的落点——
     * 在它之前，调用方（product / 过渡期的单体）只能**自己**去写那把共享的 Redis 键，
     * 而 search **没有任何内部端点**能接受"标记待同步"这个意图。
     *
     * <p>与 {@link #markDirty} 的分工（**不要混用**）：
     * <ul>
     *   <li>{@link #markDirty}：**发布方**语义 —— 首选投 MQ（亚秒级），MQ 不可用才回落 Redis；</li>
     *   <li>本方法：**兜底通道**语义 —— 只写 Redis 集合，由 {@code ProductSearchSyncTask}
     *       定时（默认 15s）批量 drain。收敛延迟因此是那个周期，不是亚秒级。</li>
     * </ul>
     * ⚠️ 为什么这里**不**顺手投 MQ：本端点存在的意义正是"补上兜底通道的缺口"（规格 §一 第 5 行
     * "补空洞，不是加功能"），而 MQ 主通道的发布方按 D1 归**product**（P6-5 #1 已落地在 product 的
     * {@code markDirty}）—— 两边都投会让同一个 spuId 被消费两次（幂等，但白干一遍活）。
     *
     * @param spuIds 要标记的 spuId；null/空/全 null ⇒ **不碰 Redis**，直接返回 0（不是错误）
     * @return 本次新增条数；**Redis 不可用 ⇒ -1**（与 {@code pendingCount()} 的 -1 同口径）
     */
    long markPending(Collection<Long> spuIds);

    /**
     * ES 集群信息快照（P6-5 #6 / D4）：集群名 / 节点名 / 版本 / 健康状态 / 节点数。
     *
     * <p>供 {@code /internal/v1/search/status} 使用（单体 {@code /api/admin/es/ping} 将转发到它）。
     * ES 不可达时返回 {@link EsClusterInfo#unavailable()}（四个 null + numberOfNodes=-1），
     * **不抛异常** —— 自检接口在 ES 挂掉时必须还能回答"我坏了"。
     */
    EsClusterInfo esClusterInfo();

    /**
     * 按 spuId 取**索引里的那篇文档**（P6-5 #5 的"按 id 取单文档"空洞）。
     *
     * <p>与 {@link #findById} 的区别是**失败口径**（这是本方法存在的理由，别合并两者）：
     * <ul>
     *   <li>文档不存在 ⇒ {@code null}（这是**结论**："索引里没有它"）；</li>
     *   <li>ES 不可达 ⇒ **抛 {@link IllegalStateException}**（由 GlobalExceptionHandler 转成 code=500）。
     *       ⚠️ 绝不返回 null —— 那会把"不可用"伪装成"不存在"，正是本项目反复禁止的那类假象
     *       （调用方会据此断言"这个商品没索引"，而真相是根本读不到）。</li>
     * </ul>
     */
    ProductSearchDoc getIndexedDoc(long spuId);

    /**
     * 标记这些商品需要重新同步(订单/售后链路调用：库存/销量变化会影响索引字段)。
     *
     * <p>两条通道，**都不在业务事务里直接写 ES**：
     * <ol>
     *   <li>优先投递 MQ 消息({@code mall.pms.es-sync})，由消费者近实时同步 —— 亚秒级</li>
     *   <li>MQ 关闭或投递失败 → 回落 Redis 集合({@code mall:es:pending})，由
     *       {@code ProductSearchSyncTask} 定时批量消费 —— 兜底通道</li>
     * </ol>
     * 两条都不可用时只告警，靠全量重建筑底。
     */
    void markDirty(Collection<Long> spuIds);

    /**
     * 尽快把单个商品同步到索引（管理端写商品后调用）。
     *
     * <p>与 {@link #markDirty} 的区别：这里要的是"改完就能搜到"，
     * 所以 MQ 关闭时**直接同步执行**（与改造前的行为一致），而不是丢进定时队列等 15 秒。
     */
    void syncLater(long spuId);

    /**
     * 尽快同步整个品牌下的在架商品（品牌改名/删除后调用）。
     * 语义同 {@link #syncLater}：MQ 可用则异步，否则同步执行。
     */
    void syncBrandLater(long brandId);

    /** 取出并移除待同步商品(队列语义，供定时任务消费) */
    List<Long> drainPending(int max);

    /** 待同步队列长度(自检/运维；Redis 不可用返回 -1) */
    long pendingCount();

    /** 读取索引中的文档数(索引不存在返回 -1，用于自检/运维) */
    long count();

    /** 当前生效的标题分词器(smartcn / cjk / standard)，用于自检展示 */
    String titleAnalyzer();

    /** 按 spuId 取索引文档(测试/排查用，不存在返回 null) */
    ProductSearchDoc findById(long spuId);

    /** 关键字检索(仅测试用；返回命中的文档，按相关性/销量) */
    List<ProductSearchDoc> searchByTitle(String keyword, int size);
}
