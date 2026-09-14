package com.mall.product.service;

import com.mall.product.support.dto.ReindexResult;
import com.mall.product.dto.ProductIdPage;
import com.mall.product.dto.ProductSearchDoc;

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

    /** 从索引删除某商品(删除商品时调用) */
    void deleteProduct(long spuId);

    /** 把某品牌下所有在架商品重写入索引(品牌改名后调用) */
    int syncByBrand(long brandId);

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
