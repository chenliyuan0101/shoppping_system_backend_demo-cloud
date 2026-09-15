package com.mall.trade.app;

import com.mall.trade.common.client.ProductClient;
import com.mall.trade.common.dto.HomeFeedVO;
import com.mall.trade.common.dto.SkuSnapshotVO;
import com.mall.trade.common.dto.SpuSnapshotVO;
import com.mall.trade.common.dto.StockLineVO;
import com.mall.trade.pms.service.ProductQueryService;
import com.mall.trade.pms.service.ProductStatQueryService;
import com.mall.trade.pms.service.StockCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 商品域三个契约的**远程实现**（P6-4 / D2）：把 {@code pms} 的本地实现换成"经远程契约读写 mall-product"。
 *
 * <h2>为什么"开关"只作测试缝，不作生产开关（D2）</h2>
 * 条件是 {@code mall.product.remote=true}（**matchIfMissing=true** ⇒ 缺省即远程），
 * 而 {@code src/main/resources/**} 的任何 yaml 里**都不出现**这个键 ⇒ 生产恒为远程实现。
 * 测试期由 {@code src/test} 的 {@code ProductTestDoubleConfig}（{@code havingValue="false"}）顶上。
 * 这样"配置写错"的方向是**安全**的：替身只在 test classpath 上，生产若被误设 false ⇒ **没有 bean ⇒ 启动即失败**，
 * 不会静默退化成"没人知道的另一套实现"（P5 的教训正是"验证静默退让"）。
 *
 * <h2>为什么用 {@code public static class} 而不是 record / lambda（P3-4 实测踩过）</h2>
 * 这些 bean 会被 AOP（事务/日志切面）代理；{@code record} 与 lambda 生成的类是 {@code final} 的，
 * CGLIB 无法代理 ⇒ **启动直接失败**。所以一律写成普通 {@code public static class}。
 *
 * <h2>空集合不发请求</h2>
 * {@code sku(id)}/{@code spu(id)} 用单元素批量（内部端点只有批量形状，规格禁止为单条猜路径）；
 * 空集合直接返回 {@code null}/{@code List.of()}，省一次远程往返 —— 列表页为空是常态，不该为此付 RTT。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "mall.product.remote", havingValue = "true", matchIfMissing = true)
public class ProductRemoteConfig {

    public ProductRemoteConfig() {
        // D2 要求：启动日志能一眼看出"现在接的是远程还是替身"
        log.info("商品域接线模式: remote=true（经 ProductClient 调 mall-product 的内部端点）");
    }

    @Bean
    public ProductQueryService productQueryServiceRemote(ProductClient client) {
        return new RemoteProductQueryService(client);
    }

    @Bean
    public ProductStatQueryService productStatQueryServiceRemote(ProductClient client) {
        return new RemoteProductStatQueryService(client);
    }

    @Bean
    public StockCommandService stockCommandServiceRemote(ProductClient client) {
        return new RemoteStockCommandService(client);
    }

    /** {@code ProductQueryService} 的远程实现（六个方法全部镜像，含单条=单元素批量） */
    public static class RemoteProductQueryService implements ProductQueryService {

        private final ProductClient client;

        public RemoteProductQueryService(ProductClient client) {
            this.client = client;
        }

        @Override
        public SkuSnapshotVO sku(Long skuId) {
            if (skuId == null) {
                return null;
            }
            List<SkuSnapshotVO> list = client.skus(List.of(skuId));
            return list.isEmpty() ? null : list.get(0);
        }

        @Override
        public List<SkuSnapshotVO> skus(Collection<Long> skuIds) {
            return client.skus(skuIds);
        }

        @Override
        public SpuSnapshotVO spu(Long spuId) {
            if (spuId == null) {
                return null;
            }
            List<SpuSnapshotVO> list = client.spus(List.of(spuId));
            return list.isEmpty() ? null : list.get(0);
        }

        @Override
        public List<SpuSnapshotVO> spus(Collection<Long> spuIds) {
            return client.spus(spuIds);
        }

        @Override
        public Map<Long, Long> minEnabledSkuPrices(Collection<Long> spuIds) {
            return client.minEnabledSkuPrices(spuIds);
        }

        @Override
        public HomeFeedVO homeFeed(int size) {
            return client.homeFeed(size);
        }
    }

    /** {@code ProductStatQueryService} 的远程实现（在架数 / 热度榜；limit 由下游夹取 [1,20]） */
    public static class RemoteProductStatQueryService implements ProductStatQueryService {

        private final ProductClient client;

        public RemoteProductStatQueryService(ProductClient client) {
            this.client = client;
        }

        @Override
        public long countEnabled() {
            return client.enabledCount();
        }

        @Override
        public List<SpuSnapshotVO> topBySales(int limit) {
            return client.topBySales(limit);
        }
    }

    /**
     * {@code StockCommandService} 的远程实现：三个写操作直通。
     *
     * <p>⚠️ 这里**不加** {@code @Transactional}：库存的原子性由商品域的那条条件 UPDATE 保证
     * （{@code stock=stock-? WHERE stock>=?}），本地事务包一个跨进程调用既无意义又会拖长行锁。
     */
    public static class RemoteStockCommandService implements StockCommandService {

        private final ProductClient client;

        public RemoteStockCommandService(ProductClient client) {
            this.client = client;
        }

        @Override
        public void reserve(String orderNo, List<StockLineVO> lines) {
            client.reserve(orderNo, lines);
        }

        @Override
        public void release(String orderNo, List<StockLineVO> lines, int changeType) {
            client.release(orderNo, lines, changeType);
        }

        @Override
        public void incrementSales(String orderNo, List<StockLineVO> lines) {
            client.incrementSales(orderNo, lines);
        }
    }
}
