package com.mall.demo.support;

import com.mall.demo.common.BusinessException;
import com.mall.demo.common.constant.EnableStatus;
import com.mall.demo.common.constant.StockChangeType;
import com.mall.demo.common.dto.CategoryNode;
import com.mall.demo.common.dto.HomeFeedVO;
import com.mall.demo.common.dto.ProductListItemVO;
import com.mall.demo.common.dto.SkuSnapshotVO;
import com.mall.demo.common.dto.SpuSnapshotVO;
import com.mall.demo.common.dto.StockLineVO;
import com.mall.demo.pms.service.ProductQueryService;
import com.mall.demo.pms.service.ProductStatQueryService;
import com.mall.demo.pms.service.StockCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>测试期的"商品域替身"</b>（P6-4 / D3）：把商品域的 3 个契约实现成
 * "直接读写 {@code mall_product} 库"的 JDBC 版本。
 *
 * <h2>为什么必须有它（这是刻意的取舍，不是偷懒）</h2>
 * P6-4 把 {@code mall_product.pms_sku}/{@code mall_product.pms_spu}/{@code mall_product.pms_sku_stock_log} 等表与本地实现整批搬进
 * {@code mall-product}（schema {@code mall_product}，与单体同一个 MySQL 实例）之后，生产环境里这 3 个契约的
 * <b>唯一</b>实现是 {@code app.ProductRemoteConfig}（HTTP → {@code mall-product}，缺省即远程）。
 * 但真库测试跑的是 MockMvc（单进程），且测试期<b>整体禁用了服务发现</b>
 * （{@code spring.cloud.nacos.discovery.enabled=false}，见 {@code src/test/resources/application.properties}）
 * —— 进程里<b>没有</b> product 可调：
 * <ul>
 *   <li>下单链路要预占库存（{@code OrderServiceImpl.createOrder} → {@code StockCommandService.reserve}）
 *       与读商品快照（{@code buildLine} → {@code sku(id)}/{@code spu(id)}，含 {@code specValues}）；</li>
 *   <li>取消/超时/后台关闭/退款要回补（{@code release}，4 条路径都经 {@code StockReleaseAfterCommit}）；</li>
 *   <li>确认收货要累加销量（{@code incrementSales}：SKU 与 SPU 同时加）；</li>
 *   <li>后台看板要"在架数 + 热度榜"（{@code AdminDashboardServiceImpl} → {@code countEnabled}/{@code topBySales}）；</li>
 *   <li>{@code /internal/v1/product/home-feed} 要"类目树 + 热门 + 新品"（{@code ProductQueryService.homeFeed}）。</li>
 * </ul>
 * 没有替身，这些套件要么全部起不来（缺 bean），要么必须依赖"本机正好开着 8102"
 * （测试不能有这种外部依赖）。<b>缺 bean 那一半是设计</b>：生产误设 {@code mall.product.remote=false} 时
 * 本类不在 classpath 上 ⇒ 没有任何实现 ⇒ <b>启动即失败</b>，不会静默退化成"没人知道的另一套实现"。
 *
 * <h2>为什么是"直读直写 {@code mall_product}"而不是别的做法</h2>
 * <ul>
 *   <li><b>这是测试专用耦合</b>：本类只存在于 {@code src/test/java}，不会被打进任何产物。它把
 *       "商品数据现在在 {@code mall_product}"这个事实显式写下来——与 {@code MySqlTestBase} 用 SQL
 *       造会员/读库存是同一件事的两个方向（写 fixture / 读 fixture）；</li>
 *   <li><b>不用 mock</b>：这些套件断言的是真库行为（库存是否真的扣了、流水是否真的写了、取消后是否真的回到基线），
 *       商品数据被 mock 掉之后，"库存扣减"测的只是一个假的计数器，没有意义；</li>
 *   <li><b>不用"起一个 mall-product"</b>：那是部署形态（见 {@code .dsh-notes/p6-4-cutover.ps1}），
 *       测试不该要求外部服务在线；</li>
 *   <li><b>不用单体残留的 MyBatis mapper</b>：{@code com.mall.demo.pms.mapper.*} 的 {@code @TableName("mall_product.pms_sku")}
 *       是<b>不带库名</b>的，会落到连接默认库 {@code mall} 的<b>冻结副本</b>上 ⇒ "对着冻结副本断言库存变化"的假绿。
 *       这正是必须写全限定名 {@code mall_product.} 的原因。</li>
 * </ul>
 *
 * <h2>⚠️ 本类是<b>测试专用镜像</b>；真值永远以 {@code mall-product} 为准</h2>
 * 逐条语义对齐（照抄，不许"顺手改好一点"）：
 * <ol>
 *   <li>{@code StockCommandServiceImpl}：按 {@code skuId} <b>升序</b>处理；预占是单条 SQL 条件扣减
 *       （判定与扣减在库里原子完成，0 行 ⇒ 409「商品库存不足：&lt;标题&gt;」）；流水 before/after 取
 *       "更新后再读"；回补绕过逻辑删除查 SKU；{@code remark} 文案与 {@code delta} 正负号照抄；</li>
 *   <li>{@code ProductQueryServiceImpl}：快照字段口径（含 {@code specValues} <b>原始 JSON</b>——
 *       {@code OrderServiceImpl} 要拿它做 {@code JsonKit.toSpecText}）；{@code minEnabledSkuPrices}
 *       只看 {@code status=1}、忽略价格为空、<b>没有可用 SKU 的 SPU 不出现在 Map 里</b>；</li>
 *   <li>{@code ProductStatQueryServiceImpl}：{@code countEnabled}/{@code topBySales} 的过滤与
 *       {@code PageKit.size(limit, 20)} 夹取；{@code homeFeed} 的"三次门户读 → 一个快照"装配
 *       （类目树 {@code status=1} 升序组树；热门 {@code sales DESC, id ASC}；新品 {@code create_time DESC, id ASC}；
 *       列表项的 {@code minPrice}/{@code totalStock}/{@code brandName} 由 {@code ProductListAssembler} 口径算出）。</li>
 * </ol>
 * <b>本类不参与任何活体判据</b>：活体判据是 P6-4 的网关切换验收 / 库存三视角一致 / 并发不超卖探针，
 * 那些跑的是真的 {@code mall-product}。替身与真实现一旦分叉，<b>以 {@code mall-product} 为准</b>，
 * 改的是本类（先例：{@code UserCenterTestDoubleConfig}）。
 *
 * <h2>⚠️ 写方法为什么必须跑在<b>独立事务</b>（{@code REQUIRES_NEW}）里——不这么做会"库存凭空多"</h2>
 * 远程实现里 {@code reserve} 是在 {@code mall-product} <b>自己的事务里先提交</b>的，
 * 调用方的本地回滚<b>覆盖不到</b>它——这正是 D1 要给 {@code reserve} 注册
 * {@code registerStockReleaseOnRollback}（回滚时反向 {@code release}）的原因。若替身跟着调用方的本地事务走：
 * <ol>
 *   <li>本地事务回滚会把替身刚做的扣减<b>一起撤销</b>（真实现不会）；</li>
 *   <li>而回滚补偿的 {@code release} 仍然会执行 ⇒ 再回补一次 ⇒ <b>库存凭空多一份</b>（超卖方向）。</li>
 * </ol>
 * 因此每个写方法整体包在一个 {@code REQUIRES_NEW} 事务里：① 与调用方事务隔离（模拟"下游已提交"）；
 * ② 三个写方法各自是属主侧的<b>一个</b> {@code @Transactional} 方法 ⇒ 多行是"全成或全败"
 * （不能用每行一个自动提交——那样第 2 行失败会留下第 1 行的扣减，真实现不会）。
 * 断言依赖这条的用例：{@code oms/CouponMySqlTest}（"lock 返回 false → 库存只扣一单"/"外层事务回滚 → 库存回到基线"）。
 *
 * <h2>刻意<b>不</b>镜像的两件事（写在这里，免得被当成漏做）</h2>
 * <ul>
 *   <li><b>{@code productSearchService.markDirty(...)}</b>（库存/销量变更后标记检索索引待同步）：
 *       那是属主实现的<b>内部副作用</b>，不是契约语义；且单体侧的 MQ 消费者与定时 drain 已在
 *       P6-4/D5 删除（队列现在由 {@code mall-search} 消费）。替身去写 {@code mall:es:pending}
 *       只会污染另一个进程的共享 Redis。</li>
 *   <li><b>Redis 缓存</b>（类目树 30min / 货架 45s）：替身一律直读 DB。套件断言的是 DB 真值，
 *       插一层缓存只会让断言看到上一个用例留下的旧值（{@code InternalApiMySqlTest#homeFeed} 尤其明显）。</li>
 * </ul>
 *
 * <p>接线方式：类上是 {@code @ConditionalOnProperty(name="mall.product.remote", havingValue="false")}，
 * 由 {@code MySqlTestBase} 的 {@code @TestPropertySource} 注入 {@code mall.product.remote=false} 打开；
 * 生产侧 main 的 yaml <b>不出现</b>该键，且 {@code ProductRemoteConfig} 是 {@code matchIfMissing=true}
 * ⇒ 生产恒为远程实现（D2）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "mall.product.remote", havingValue = "false")
public class ProductTestDoubleConfig {

    public ProductTestDoubleConfig() {
        // D2 要求：启动日志能一眼看出"现在接的是远程还是替身"
        log.info("商品域接线模式: remote=false（测试替身 ProductTestDoubleConfig，直读直写 mall_product）");
    }

    @Bean
    @ConditionalOnMissingBean
    public ProductQueryService productQueryService(JdbcTemplate jdbcTemplate) {
        return new JdbcProductQuery(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProductStatQueryService productStatQueryService(JdbcTemplate jdbcTemplate) {
        return new JdbcProductStatQuery(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public StockCommandService stockCommandService(JdbcTemplate jdbcTemplate,
                                                   PlatformTransactionManager transactionManager) {
        return new JdbcStockCommand(jdbcTemplate, transactionManager);
    }

    // ==================== 商品查询（只读） ====================

    /**
     * {@code ProductQueryService} 替身：字段口径照 {@code ProductQueryServiceImpl} +
     * {@code ProductListAssembler}（首页区块的最低/总库存就是它算的）。
     *
     * <p>每条查询都显式写 {@code deleted = 0}——属主实现走 MyBatis-Plus，{@code @TableLogic} 会自动加这个条件
     * （{@code SkuMapper} 的注解 SQL 里那句"注解 SQL 不经过 MP 的 @TableLogic"就是这条的反面证据）；
     * 这里手写等价条件，避免"被逻辑删除的商品在测试里还能查到"的假绿。
     */
    static class JdbcProductQuery implements ProductQueryService {

        /** 首页每个区块的条数：与属主实现 {@code ProductQueryServiceImpl.HOME_SECTION_SIZE} 一致 */
        private static final int HOME_SECTION_SIZE = 8;

        /** 首页区块条数上限：调用方传参不可信，超出即回落默认值（同属主实现） */
        private static final int HOME_SECTION_MAX = 50;

        private static final String SKU_COLUMNS =
                "id, spu_id, price, original_price, image, spec_values, stock, status";

        private static final String SPU_COLUMNS = "id, title, subtitle, main_image, sales, status";

        private static final RowMapper<SkuSnapshotVO> SKU_MAPPER = (rs, i) -> new SkuSnapshotVO(
                rs.getObject("id", Long.class),
                rs.getObject("spu_id", Long.class),
                rs.getObject("price", Long.class),
                rs.getObject("original_price", Long.class),
                rs.getString("image"),
                // spec_values 是 json 列，契约刻意保持"原始 JSON 字符串"（展示方自己 JsonKit.toSpecText）
                rs.getString("spec_values"),
                rs.getObject("stock", Integer.class),
                rs.getObject("status", Integer.class));

        private static final RowMapper<SpuSnapshotVO> SPU_MAPPER = (rs, i) -> new SpuSnapshotVO(
                rs.getObject("id", Long.class),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                rs.getObject("sales", Integer.class),
                rs.getObject("status", Integer.class));

        /** 货架读所需的 SPU 列（比契约快照多 categoryId/brandId/createTime，用于装配列表项） */
        private static final RowMapper<SpuRow> SHELF_SPU_MAPPER = (rs, i) -> {
            Timestamp createTime = rs.getTimestamp("create_time");
            return new SpuRow(rs.getLong("id"),
                    rs.getObject("category_id", Long.class),
                    rs.getObject("brand_id", Long.class),
                    rs.getString("title"),
                    rs.getString("subtitle"),
                    rs.getString("main_image"),
                    rs.getObject("status", Integer.class),
                    rs.getObject("sales", Integer.class),
                    createTime == null ? null : createTime.toLocalDateTime());
        };

        private final JdbcTemplate jdbc;

        JdbcProductQuery(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public SkuSnapshotVO sku(Long skuId) {
            if (skuId == null) {
                return null;
            }
            List<SkuSnapshotVO> rows = jdbc.query(
                    "SELECT " + SKU_COLUMNS + " FROM mall_product.pms_sku WHERE id = ? AND deleted = 0",
                    SKU_MAPPER, skuId);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public List<SkuSnapshotVO> skus(Collection<Long> skuIds) {
            // 空集合直接返回，不发无意义查询（契约与属主实现同口径）
            if (skuIds == null || skuIds.isEmpty()) {
                return List.of();
            }
            // ORDER BY id：给用例确定性的顺序（幂等；InnoDB 主键 IN 查询的自然顺序也是升序）
            return Sql.byIds(jdbc,
                    "SELECT " + SKU_COLUMNS + " FROM mall_product.pms_sku"
                            + " WHERE deleted = 0 AND id IN (%s) ORDER BY id",
                    skuIds, SKU_MAPPER);
        }

        @Override
        public SpuSnapshotVO spu(Long spuId) {
            if (spuId == null) {
                return null;
            }
            List<SpuSnapshotVO> rows = jdbc.query(
                    "SELECT " + SPU_COLUMNS + " FROM mall_product.pms_spu WHERE id = ? AND deleted = 0",
                    SPU_MAPPER, spuId);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public List<SpuSnapshotVO> spus(Collection<Long> spuIds) {
            if (spuIds == null || spuIds.isEmpty()) {
                return List.of();
            }
            return Sql.byIds(jdbc,
                    "SELECT " + SPU_COLUMNS + " FROM mall_product.pms_spu"
                            + " WHERE deleted = 0 AND id IN (%s) ORDER BY id",
                    spuIds, SPU_MAPPER);
        }

        /**
         * 每个 SPU 下<b>启用</b> SKU 的最低价：{@code status=1} + 价格非空 + 按 SPU 取最小。
         *
         * <p>{@code GROUP BY} 天然满足"没有可用 SKU 的 SPU 不出现在结果里"；属主实现在内存里用
         * {@code Collectors.toMap(..., Math::min)} 达到同一效果（{@code deleted = 0} 由 {@code @TableLogic} 附上）。
         */
        @Override
        public Map<Long, Long> minEnabledSkuPrices(Collection<Long> spuIds) {
            Set<Long> distinct = Sql.distinct(spuIds);
            if (distinct.isEmpty()) {
                return Map.of();
            }
            List<SpuMinPrice> rows = jdbc.query("SELECT spu_id, MIN(price) AS min_price FROM mall_product.pms_sku"
                            + " WHERE deleted = 0 AND status = ? AND price IS NOT NULL"
                            + " AND spu_id IN (" + Sql.placeholders(distinct) + ")"
                            + " GROUP BY spu_id ORDER BY spu_id",
                    (rs, i) -> new SpuMinPrice(rs.getLong("spu_id"), rs.getLong("min_price")),
                    Sql.args(EnableStatus.ENABLED, distinct));
            Map<Long, Long> result = new LinkedHashMap<>();
            for (SpuMinPrice row : rows) {
                result.put(row.spuId(), row.minPrice());
            }
            return result;
        }

        /**
         * 首页区块：与属主实现同一次装配（三次门户读 → 一个快照），条数夹取也照抄
         * （{@code ≤0 或 >50} ⇒ 回落 8）。
         */
        @Override
        public HomeFeedVO homeFeed(int size) {
            int limit = (size <= 0 || size > HOME_SECTION_MAX) ? HOME_SECTION_SIZE : size;
            return HomeFeedVO.builder()
                    .categories(enabledCategoryTree())
                    .hotProducts(onShelf("sales", limit))
                    .newProducts(onShelf("newest", limit))
                    .build();
        }

        /**
         * 启用类目树（{@code status=1}，按 {@code sort, id} 升序组树，同属主实现的可视顺序）。
         *
         * <p>组树算法与属主实现的 {@code CategoryTreeBuilder.build} 逐字同构（{@code parentId==null||0}
         * 为顶级、其余挂到父节点的 {@code children} 下）。这里刻意<b>不</b> import demo 侧残留的
         * {@code pms.support.CategoryTreeBuilder}/{@code pms.domain.Category}：那个包属于 P6-6 要整包删除的
         * 遗留物，替身必须能活过那一步（而且它是"测试代码依赖商品域内部实现"的假耦合，
         * 替身只该依赖契约 DTO）。
         */
        private List<CategoryNode> enabledCategoryTree() {
            List<CategoryRow> all = jdbc.query("SELECT id, parent_id, name, sort, status"
                            + " FROM mall_product.pms_category WHERE status = ? ORDER BY sort ASC, id ASC",
                    (rs, i) -> new CategoryRow(rs.getLong("id"),
                            rs.getObject("parent_id", Long.class),
                            rs.getString("name"),
                            rs.getObject("sort", Integer.class),
                            rs.getObject("status", Integer.class)),
                    EnableStatus.ENABLED);
            Map<Long, List<CategoryRow>> byParent = new LinkedHashMap<>();
            for (CategoryRow row : all) {
                byParent.computeIfAbsent(row.parentId() == null ? 0L : row.parentId(), k -> new ArrayList<>())
                        .add(row);
            }
            List<CategoryNode> roots = new ArrayList<>();
            for (CategoryRow row : all) {
                if (row.parentId() == null || row.parentId() == 0) {
                    CategoryNode node = toNode(row);
                    node.setChildren(toNodes(byParent.getOrDefault(row.id(), List.of()), byParent));
                    roots.add(node);
                }
            }
            return roots;
        }

        /** 递归挂子节点（同属主实现：任意层级，不限两级） */
        private static List<CategoryNode> toNodes(List<CategoryRow> rows, Map<Long, List<CategoryRow>> byParent) {
            List<CategoryNode> nodes = new ArrayList<>();
            for (CategoryRow row : rows) {
                CategoryNode node = toNode(row);
                node.setChildren(toNodes(byParent.getOrDefault(row.id(), List.of()), byParent));
                nodes.add(node);
            }
            return nodes;
        }

        private static CategoryNode toNode(CategoryRow row) {
            CategoryNode node = new CategoryNode();
            node.setId(row.id());
            node.setParentId(row.parentId());
            node.setName(row.name());
            node.setSort(row.sort());
            node.setStatus(row.status());
            return node;
        }

        /**
         * 首页区块的货架读：无筛选条件（{@code keyword/categoryId/brandId/minPrice/maxPrice} 全空），
         * 排序即"热门(sales)/新品(newest)"——与 {@code SpuMapper.SHELF_ORDER} 的固定片段逐字对齐
         * （都带 {@code id ASC} 兜底，保证同值时顺序稳定）。
         */
        private List<ProductListItemVO> onShelf(String sort, int limit) {
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM mall_product.pms_spu"
                    + " WHERE deleted = 0 AND status = ?", Long.class, EnableStatus.ENABLED);
            if (total == null || total == 0) {
                return List.of();   // 货架为空是常态：不再发第二条查询（与属主实现的 total==0 短路一致）
            }
            String order = "newest".equals(sort)
                    ? " ORDER BY create_time DESC, id ASC"
                    : " ORDER BY sales DESC, id ASC";
            List<SpuRow> spus = jdbc.query("SELECT id, category_id, brand_id, title, subtitle, main_image,"
                            + " status, sales, create_time FROM mall_product.pms_spu"
                            + " WHERE deleted = 0 AND status = ?" + order + " LIMIT ?, ?",
                    SHELF_SPU_MAPPER, EnableStatus.ENABLED, 0L, (long) limit);
            return assemble(spus);
        }

        /**
         * 列表项装配：与 {@code ProductListAssembler.assemble} 口径一致——
         * 批量取 SKU（只过滤逻辑删除，<b>不</b>过滤 status）算 {@code minPrice}（价格全空 ⇒ 0）与
         * {@code totalStock}（null 记 0），并按 brandId 回填品牌名。
         */
        private List<ProductListItemVO> assemble(List<SpuRow> spus) {
            if (spus.isEmpty()) {
                return List.of();
            }
            Set<Long> spuIds = new LinkedHashSet<>(spus.stream().map(SpuRow::id).toList());
            List<SkuRow> skuRows = jdbc.query("SELECT spu_id, price, stock FROM mall_product.pms_sku"
                            + " WHERE deleted = 0 AND spu_id IN (" + Sql.placeholders(spuIds) + ") ORDER BY id",
                    (rs, i) -> new SkuRow(rs.getLong("spu_id"),
                            rs.getObject("price", Long.class), rs.getObject("stock", Integer.class)),
                    spuIds.toArray());
            Map<Long, List<SkuRow>> skuBySpu = new LinkedHashMap<>();
            for (SkuRow row : skuRows) {
                skuBySpu.computeIfAbsent(row.spuId(), k -> new ArrayList<>()).add(row);
            }

            Set<Long> brandIds = new LinkedHashSet<>();
            for (SpuRow spu : spus) {
                if (spu.brandId() != null) {
                    brandIds.add(spu.brandId());
                }
            }
            Map<Long, String> brandNames = new LinkedHashMap<>();
            if (!brandIds.isEmpty()) {
                List<BrandRow> brandRows = jdbc.query("SELECT id, name FROM mall_product.pms_brand"
                                + " WHERE id IN (" + Sql.placeholders(brandIds) + ")",
                        (rs, i) -> new BrandRow(rs.getLong("id"), rs.getString("name")),
                        brandIds.toArray());
                for (BrandRow row : brandRows) {
                    brandNames.put(row.id(), row.name());
                }
            }

            List<ProductListItemVO> result = new ArrayList<>(spus.size());
            for (SpuRow spu : spus) {
                List<SkuRow> skus = skuBySpu.getOrDefault(spu.id(), List.of());
                long minPrice = skus.stream().filter(s -> s.price() != null)
                        .mapToLong(SkuRow::price).min().orElse(0);
                int totalStock = skus.stream().mapToInt(s -> s.stock() == null ? 0 : s.stock()).sum();

                ProductListItemVO vo = new ProductListItemVO();
                vo.setSpuId(spu.id());
                vo.setTitle(spu.title());
                vo.setSubtitle(spu.subtitle());
                vo.setMainImage(spu.mainImage());
                vo.setCategoryId(spu.categoryId());
                vo.setBrandId(spu.brandId());
                vo.setBrandName(spu.brandId() == null ? null : brandNames.get(spu.brandId()));
                vo.setStatus(spu.status());
                vo.setSales(spu.sales());
                vo.setMinPrice(minPrice);
                vo.setTotalStock(totalStock);
                vo.setCreateTime(spu.createTime());
                result.add(vo);
            }
            return result;
        }

        /** 货架读的 SPU 行（比契约快照多装配列表项需要的列） */
        private record SpuRow(Long id, Long categoryId, Long brandId, String title, String subtitle,
                              String mainImage, Integer status, Integer sales, LocalDateTime createTime) {
        }

        /** 装配用的 SKU 行（只取所属 SPU、价格、库存三列） */
        private record SkuRow(long spuId, Long price, Integer stock) {
        }

        /** 装配用的品牌行（只要 id 与名称） */
        private record BrandRow(long id, String name) {
        }

        /** 启用类目行（组树用，只取展示需要的列） */
        private record CategoryRow(long id, Long parentId, String name, Integer sort, Integer status) {
        }

        /** {@code minEnabledSkuPrices} 的聚合行 */
        private record SpuMinPrice(long spuId, long minPrice) {
        }
    }

    // ==================== 商品看板统计（只读） ====================

    /**
     * {@code ProductStatQueryService} 替身：过滤（{@code status=1} 且未逻辑删除）、排序、
     * 取前 N 全部下推到 SQL——与属主实现"不捞全表再内存排序"的口径一致。
     */
    static class JdbcProductStatQuery implements ProductStatQueryService {

        /** 榜单条数上限：与属主实现 {@code MAX_TOP_LIMIT} 一致 */
        private static final int MAX_TOP_LIMIT = 20;

        private static final RowMapper<SpuSnapshotVO> SPU_MAPPER = (rs, i) -> new SpuSnapshotVO(
                rs.getObject("id", Long.class),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                rs.getObject("sales", Integer.class),
                rs.getObject("status", Integer.class));

        private final JdbcTemplate jdbc;

        JdbcProductStatQuery(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public long countEnabled() {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM mall_product.pms_spu"
                    + " WHERE deleted = 0 AND status = ?", Long.class, EnableStatus.ENABLED);
            return count == null ? 0L : count;
        }

        @Override
        public List<SpuSnapshotVO> topBySales(int limit) {
            // limit 夹取 [1,20]（PageKit.size）：与属主实现同一条规则
            int n = (int) Math.min(MAX_TOP_LIMIT, Math.max(1L, (long) limit));
            // 刻意不加 id 兜底排序：属主实现就是 ORDER BY sales DESC + LIMIT 0,n（不看 id）
            return jdbc.query("SELECT id, title, subtitle, main_image, sales, status FROM mall_product.pms_spu"
                            + " WHERE deleted = 0 AND status = ? ORDER BY sales DESC LIMIT 0, ?",
                    SPU_MAPPER, EnableStatus.ENABLED, n);
        }
    }

    // ==================== 库存 / 销量写 ====================

    /**
     * {@code StockCommandService} 替身：逐条镜像 {@code StockCommandServiceImpl}。
     *
     * <p>三个方法各自跑在一个 {@code REQUIRES_NEW} 事务里（原因见类注释：模拟"下游已提交" +
     * 保持"多行全成或全败"）。属主实现的 {@code UPDATE} 经 MyBatis-Plus 的 {@code @TableLogic}
     * 会自动附加 {@code deleted = 0}，这里手写等价条件。
     */
    static class JdbcStockCommand implements StockCommandService {

        private final JdbcTemplate jdbc;
        private final TransactionTemplate requiresNew;

        JdbcStockCommand(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
            this.jdbc = jdbc;
            this.requiresNew = new TransactionTemplate(transactionManager);
            this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        /**
         * 预占库存：按 {@code skuId} 升序逐行条件扣减。
         *
         * <p>扣减 SQL 与属主实现等价：{@code stock = stock - ?}，条件 {@code id + status=1 + stock>=quantity}
         * （判定与扣减在一条 SQL 里原子完成 ⇒ 并发不超卖）；影响 0 行即库存不足。
         * 成功后再读一次 stock 写流水（"更新后再读"：同一事务内可见自己刚写入的值，before/after 才是真实值）。
         */
        @Override
        public void reserve(String orderNo, List<StockLineVO> lines) {
            List<StockLineVO> sorted = sortedBySkuId(lines);
            if (sorted.isEmpty()) {
                return;
            }
            requiresNew.executeWithoutResult(status -> {
                for (StockLineVO line : sorted) {
                    int affected = jdbc.update("UPDATE mall_product.pms_sku"
                                    + " SET stock = stock - ?, update_time = NOW()"
                                    + " WHERE id = ? AND status = ? AND stock >= ? AND deleted = 0",
                            line.getQuantity(), line.getSkuId(), EnableStatus.ENABLED, line.getQuantity());
                    if (affected == 0) {
                        // 文案逐字：注意与 OrderServiceImpl 的"库存不足：<标题>"（下单前校验）不是同一条
                        throw new BusinessException(409, "商品库存不足：" + spuTitleOf(line));
                    }
                    Integer afterStock = jdbc.queryForObject("SELECT stock FROM mall_product.pms_sku"
                            + " WHERE id = ? AND deleted = 0", Integer.class, line.getSkuId());
                    writeLog(orderNo, line.getSkuId(), StockChangeType.ORDER_DEDUCT,
                            -line.getQuantity(), afterStock);
                }
            });
        }

        /**
         * 回补库存：把数量加回 + 按 {@code changeType} 写一条流水（{@code delta} 为正，remark 见
         * {@link #remarkOf(int)}）。
         *
         * <p>与属主实现一致的两个细节：
         * <ol>
         *   <li><b>判断 SKU 存在时绕过逻辑删除</b>（后台改商品是"逻辑删除旧 SKU + 重建"；若直接按
         *       {@code deleted = 0} 查会得到 null 而跳过，历史订单的库存就静默不回补了）；行真的没了才告警跳过；</li>
         *   <li>加回后的 after 也按"绕过逻辑删除"再读（属主实现要的是"实际落库的值"，含被逻辑删除的行）。</li>
         * </ol>
         */
        @Override
        public void release(String orderNo, List<StockLineVO> lines, int changeType) {
            List<StockLineVO> sorted = sortedBySkuId(lines);
            if (sorted.isEmpty()) {
                return;
            }
            requiresNew.executeWithoutResult(status -> {
                for (StockLineVO line : sorted) {
                    Integer current = rawStock(line.getSkuId());
                    if (current == null) {
                        log.warn("回补库存时 SKU 已不存在(需人工核对): orderNo={} skuId={} qty={}",
                                orderNo, line.getSkuId(), line.getQuantity());
                        continue;
                    }
                    jdbc.update("UPDATE mall_product.pms_sku SET stock = stock + ?"
                                    + " WHERE id = ? AND deleted = 0",
                            line.getQuantity(), line.getSkuId());
                    Integer raw = rawStock(line.getSkuId());
                    Integer afterStock = raw == null ? current + line.getQuantity() : raw;
                    writeLog(orderNo, line.getSkuId(), changeType, line.getQuantity(), afterStock);
                }
            });
        }

        /**
         * 累加销量：SKU 与 SPU <b>同时</b>加（SPU 那条只在 {@code line.spuId} 非空时执行）。
         *
         * <p>销量是<b>无条件</b>累加（重复调用会重复累加）——属主实现的契约注释写明"调用方必须已经
         * 在'待收货 → 已完成'的 CAS 抢到之后才调这里"，替身不额外加幂等（加了反而与真值分叉）。
         */
        @Override
        public void incrementSales(String orderNo, List<StockLineVO> lines) {
            List<StockLineVO> sorted = sortedBySkuId(lines);
            if (sorted.isEmpty()) {
                return;
            }
            requiresNew.executeWithoutResult(status -> {
                for (StockLineVO line : sorted) {
                    jdbc.update("UPDATE mall_product.pms_sku SET sales = sales + ?"
                            + " WHERE id = ? AND deleted = 0", line.getQuantity(), line.getSkuId());
                    if (line.getSpuId() != null) {
                        jdbc.update("UPDATE mall_product.pms_spu SET sales = sales + ?"
                                + " WHERE id = ? AND deleted = 0", line.getQuantity(), line.getSpuId());
                    }
                }
            });
        }

        /** 按 {@code skuId} 升序：保证多 SKU 事务的加锁顺序一致（与属主实现同一个 Comparator） */
        private static List<StockLineVO> sortedBySkuId(List<StockLineVO> lines) {
            if (lines == null || lines.isEmpty()) {
                return List.of();
            }
            return lines.stream()
                    .sorted(Comparator.comparing(StockLineVO::getSkuId))
                    .toList();
        }

        /** 库存不足时的提示要带商品标题——按契约行的 {@code spuId} 查（只发生在失败路径上） */
        private String spuTitleOf(StockLineVO line) {
            if (line.getSpuId() == null) {
                return "";
            }
            List<String> titles = jdbc.queryForList("SELECT title FROM mall_product.pms_spu"
                    + " WHERE id = ? AND deleted = 0", String.class, line.getSpuId());
            return titles.isEmpty() || titles.get(0) == null ? "" : titles.get(0);
        }

        /** 绕过逻辑删除读原始行的 stock（不存在返回 null） */
        private Integer rawStock(Long skuId) {
            List<Integer> rows = jdbc.queryForList("SELECT stock FROM mall_product.pms_sku WHERE id = ?",
                    Integer.class, skuId);
            return rows.isEmpty() ? null : rows.get(0);
        }

        /**
         * 写库存流水：列口径照属主实现（{@code SkuStockLog} 实体只填这几个字段，
         * {@code create_time} 由库默认值 {@code CURRENT_TIMESTAMP} 填；{@code operator_id} 留 null）。
         * {@code before = after - delta}（扣减时 delta 为负 ⇒ before 更大）。
         */
        private void writeLog(String orderNo, Long skuId, int changeType, int delta, Integer afterStock) {
            Integer beforeStock = afterStock == null ? null : afterStock - delta;
            jdbc.update("INSERT INTO mall_product.pms_sku_stock_log"
                            + " (sku_id, order_no, change_type, delta, before_stock, after_stock, remark)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    skuId, orderNo, changeType, delta, beforeStock, afterStock, remarkOf(changeType));
        }

        /** 流水文案与属主实现逐字一致（订单侧三型 + 退款侧一型，其余归"取消回补"） */
        private static String remarkOf(int changeType) {
            return switch (changeType) {
                case StockChangeType.ORDER_DEDUCT -> "下单扣减";
                case StockChangeType.TIMEOUT_RESTORE -> "超时关单回补";
                case StockChangeType.REFUND_RESTORE -> "退款回补";
                default -> "取消回补";
            };
        }
    }

    // ==================== SQL helpers ====================

    /** 动态 {@code IN} 查询的小工具（占位符按 id 个数生成，参数走绑定，不拼字符串值） */
    static final class Sql {

        private Sql() {
        }

        /** 去掉 null 与重复的 id，保持首次出现顺序 */
        static Set<Long> distinct(Collection<Long> ids) {
            Set<Long> distinct = new LinkedHashSet<>();
            if (ids == null) {
                return distinct;
            }
            for (Long id : ids) {
                if (id != null) {
                    distinct.add(id);
                }
            }
            return distinct;
        }

        /** {@code n} 个 {@code ?} 占位符 */
        static String placeholders(Collection<?> values) {
            return String.join(", ", values.stream().map(v -> "?").toList());
        }

        /** 首个参数 + id 集合拼成的参数数组（配合 {@link #placeholders(Collection)} 使用） */
        static Object[] args(Object first, Collection<Long> ids) {
            List<Object> args = new ArrayList<>(ids.size() + 1);
            args.add(first);
            args.addAll(ids);
            return args.toArray();
        }

        static <T> List<T> byIds(JdbcTemplate jdbc, String sqlTemplate, Collection<Long> ids, RowMapper<T> mapper) {
            Set<Long> distinct = distinct(ids);
            if (distinct.isEmpty()) {
                return List.of();
            }
            return jdbc.query(sqlTemplate.formatted(placeholders(distinct)), mapper, distinct.toArray());
        }
    }
}
