package com.mall.product.mq;

import com.mall.product.support.MqMessages;
import com.mall.product.support.MqTopology;
import com.mall.product.support.TxCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 商品索引同步的**生产方**（P6-5 #1）：把"这些 spuId / 这个品牌的索引需要重算"投到
 * {@code mall.pms.sync}，由 {@code mall-search} 的消费者落到 ES。
 *
 * <h2>为什么本批要补回发布方</h2>
 * P6-4 把商品真值搬到 {@code mall_product} 并把单体侧的索引写方删掉之后，本服务成了唯一写方，
 * 但<b>没有发布方</b>：库存/销量变化只能写本地 Redis 待同步集合，靠 search 的定时任务
 * <b>15s</b> 收敛（单体时代 MQ 是<b>亚秒级</b>）。这是 P6-4 显式记账的残留，也是"下单后搜索结果里的
 * 库存/销量慢半拍"的成因。本类的形状与单体时代的 {@code ProductSearchServiceImpl#markDirty} 逐字同语义：
 * <b>MQ 主 + Redis 兜底</b>。
 *
 * <h2>三条必须守住的语义</h2>
 * <ol>
 *   <li><b>事务提交后才投递</b>（{@link TxCallbacks#afterCommitOrNow}）：否则消费方可能先于提交读库，
 *       把<b>旧值</b>写进索引 —— 就是 P6-4 窗口实测到的"幽灵文档"同一类缺陷（详见 {@code TxCallbacks}）。
 *       ⚠️ 调用方（{@code StockCommandServiceImpl}）是在<b>事务内</b>调用 {@code markDirty} 的，
 *       所以这条必须由本类兜住，而不是指望调用方。</li>
 *   <li><b>失败必须能被兜住</b>：{@code publishXxx(…, Runnable onFailure)} 在"Mq 关闭 / 投递抛异常"时执行兜底
 *       （写 Redis 待同步集合或直接同步）。投递成功与否以"消息已交给 broker"为准 —— 见下面的**边界**。</li>
 *   <li><b>分批</b>：按 {@link ProductSyncMessage#MAX_BATCH}（100）拆消息，避免一批库存变更撑出巨型消息。</li>
 * </ol>
 *
 * <h2>边界（如实说明，别当它更强）</h2>
 * <ul>
 *   <li>本类造的是<b>首投</b>消息（不带 {@code mall-retry-count} 头）；重试与死信投递是<b>消费方</b>的职责
 *       （{@code mall-search} 的 {@code ProductSyncConsumer} + 它自己的 publisher），本服务不消费该队列。</li>
 *   <li>{@code send} 成功 = 消息已被 broker 接收；<b>"交换机存在但没有队列绑定"这种路由问题这里兜不住</b>
 *       （publisher-returns 是异步回调，拿不到同步结论）。那是 broker 拓扑问题，会在 search 的队列深度/死信上暴露；
 *       本服务只声明交换机、不声明队列，正是为了避免"两边参数不一致导致 PRECONDITION_FAILED"。</li>
 *   <li>{@code mall.mq.enabled=false}（含本模块的全部真库用例）⇒ 本类一律返回 false、只走兜底通道，
 *       与"MQ 不可用"同一条路径 ⇒ 单测的成败不取决于外面是否正好跑着 broker。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSyncPublisher {

    private final RabbitTemplate rabbitTemplate;

    /** 与单体/其它服务同一个开关（{@code matchIfMissing=true}：不配就是开） */
    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    // ==================================================================
    // 库存/销量变更：标记待同步（管理端链路与订单链路共用）
    // ==================================================================

    /**
     * 投递"按 spuId 同步"消息（自动按 {@link ProductSyncMessage#MAX_BATCH} 分批）。
     *
     * @return 是否**全部**投递成功（MQ 关闭或无有效 id ⇒ false）
     */
    public boolean publishSpuIds(Collection<Long> spuIds) {
        List<Long> ids = normalize(spuIds);
        if (!mqEnabled || ids.isEmpty()) {
            return false;
        }
        boolean allOk = true;
        for (List<Long> batch : batches(ids)) {
            allOk &= sendFresh(ProductSyncMessage.ofSpuIds(batch));
        }
        return allOk;
    }

    /**
     * **业务链路的推荐入口**：事务提交后投递；MQ 关闭或投递失败时执行 {@code onFailure} 兜底。
     *
     * @param onFailure 兜底动作（库存/销量链路 = 写 Redis 待同步集合；管理端链路 = 直接同步一次）
     */
    public void publishSpuIds(Collection<Long> spuIds, Runnable onFailure) {
        List<Long> ids = normalize(spuIds);
        if (!mqEnabled || ids.isEmpty()) {
            // ⚠️ 即使走兜底也要等提交后：兜底动作之一是"直接同步写 ES"，放事务内会让 ES 的超时压住行锁，
            //    并产生"库里没提交、索引已可见"的脏文档（单体的同类注释，实测教训）
            TxCallbacks.afterCommitOrNow(onFailure);
            return;
        }
        TxCallbacks.afterCommitOrNow(() -> {
            boolean ok = true;
            for (List<Long> batch : batches(ids)) {
                ok &= sendFresh(ProductSyncMessage.ofSpuIds(batch));
            }
            if (!ok) {
                runQuietly(onFailure);
            }
        });
    }

    // ==================================================================
    // 品牌改名/删除：整品牌重算
    // ==================================================================

    /** 投递"按品牌同步"消息；返回是否投递成功 */
    public boolean publishBrand(long brandId) {
        if (!mqEnabled || brandId <= 0) {
            return false;
        }
        return sendFresh(ProductSyncMessage.ofBrand(brandId));
    }

    /** 事务提交后投递"按品牌同步"；失败执行兜底 */
    public void publishBrand(long brandId, Runnable onFailure) {
        if (!mqEnabled || brandId <= 0) {
            TxCallbacks.afterCommitOrNow(onFailure);
            return;
        }
        TxCallbacks.afterCommitOrNow(() -> {
            if (!sendFresh(ProductSyncMessage.ofBrand(brandId))) {
                runQuietly(onFailure);
            }
        });
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private boolean sendFresh(ProductSyncMessage payload) {
        try {
            String messageId = payload.isBrand()
                    ? "pms-sync-brand-" + payload.brandId()
                    : "pms-sync-" + payload.spuIds().size() + "-" + payload.spuIds().get(0);
            rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_ROUTING_KEY,
                    MqMessages.json(payload, messageId));
            log.debug("商品索引同步消息已投递: spuIds={} brandId={}", payload.spuIds(), payload.brandId());
            return true;
        } catch (Exception e) {
            // 不抛：索引同步失败不该阻塞商品写（与单体同口径）
            log.warn("商品索引同步消息投递失败(将回落 Redis 待同步集合): {}", e.getMessage());
            return false;
        }
    }

    /** 兜底动作本身也不能影响主链路 */
    private void runQuietly(Runnable onFailure) {
        if (onFailure == null) {
            return;
        }
        try {
            onFailure.run();
        } catch (Exception e) {
            log.error("索引同步兜底动作执行失败(等全量重建筑底): {}", e.getMessage());
        }
    }

    private static List<Long> normalize(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        return spuIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    /** 按 {@link ProductSyncMessage#MAX_BATCH} 分批，避免单条消息过大 */
    private static List<List<Long>> batches(List<Long> ids) {
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += ProductSyncMessage.MAX_BATCH) {
            result.add(List.copyOf(ids.subList(i, Math.min(i + ProductSyncMessage.MAX_BATCH, ids.size()))));
        }
        return result;
    }
}
