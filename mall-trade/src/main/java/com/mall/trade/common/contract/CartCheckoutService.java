package com.mall.trade.common.contract;

import com.mall.trade.common.dto.CartClaimResultVO;
import com.mall.trade.common.dto.CartItemSnapshotVO;

import java.util.Collection;
import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 购物车<b>结算契约</b>（供下单链路使用）。
 *
 * <p>本接口只服务结算这一件事——<b>读要结算的条目</b>与<b>原子领取这些条目</b>。
 * 用户端的购物车增删改查（{@code /api/cart/**}）已经由网关直接路由到 user-center，
 * 单体侧拿不到"改数量/加购/查整个购物车"的权限面。
 *
 * <p>⚠️ {@link #consumeItems} 的语义不是"删几条数据"，而是<b>结算闸门</b>：
 * 条件删除的影响行数就是并发去重凭证——两个并发请求只有一个能把条目删掉。
 * 这是当前工作区里"用一条 SQL 的原子性替代分布式锁"的关键设计（详见《微服务改造方案.md》§4.1 步骤 ①）。
 */
public interface CartCheckoutService {

    /**
     * 读取要结算的购物车条目。
     *
     * @param memberId 会员 id（只会返回该会员的条目）
     * @param itemIds  条目 id 集合
     * @return 条目快照；<b>不存在或不属于该会员的 id 不会出现在结果里</b>
     *         （调用方用"返回条数是否等于请求条数"判断是否存在非法条目）
     */
    List<CartItemSnapshotVO> items(Long memberId, Collection<Long> itemIds);

    /**
     * 结算闸门（旧形态）：原子删除这些条目，作为"本次结算已消费掉它们"的证明。
     *
     * <p>调用方必须在扣库存<b>之前</b>调用它：否则不带幂等键的双击提交会各扣一遍库存、各落一单。
     *
     * <p>⚠️ <b>P3 起新代码请用 {@link #claim} + {@link #restore} 两阶段形态</b>。
     * 本方法的问题在拆服务后暴露：它依赖"远程删除会被本地事务回滚"这个前提——
     * 购物车搬到 user-center 之后，回滚不再覆盖它，失败路径会把用户的购物车明细永久吞掉。
     * 单体侧已无调用方（{@code OrderServiceImpl} 走 claim/restore），保留签名只为把这条语义留档：
     * 远程实现里它<b>显式抛 UnsupportedOperationException</b>，不允许被悄悄用起来。
     *
     * @return true=全部删除成功（抢到闸门）；false=至少一条已被删除/不属于该会员（调用方按 409 整体回滚）
     */
    boolean consumeItems(Long memberId, Collection<Long> itemIds);

    /**
     * 结算闸门（新形态，P3）：以 {@code orderNo} 为<b>幂等键</b>领取这些条目。
     *
     * <p>与 {@link #consumeItems} 的差别（这是拆服务后必须有的语义）：
     * <ul>
     *   <li><b>同一订单重放 → 视为已领取</b>（{@code claimed=true} 且返回当时存下的明细快照），
     *       而不是"0 行 → 已被别人结算"——用户双击提交不该被判成冲突；</li>
     *   <li><b>不同订单</b>抢同一批明细 → 仍然只有一方领到（另一方 {@code claimed=false} → 409）；</li>
     *   <li>领取记录可被 {@link #restore} 归还，因此"失败回滚"不再依赖本地事务。</li>
     * </ul>
     *
     * @param orderNo 订单号（幂等键；调用方保证同一次下单逻辑只用一个）
     */
    CartClaimResultVO claim(Long memberId, String orderNo, Collection<Long> itemIds);

    /**
     * 补偿：把某个订单已领取的明细还回购物车（订单事务回滚时调用）。
     *
     * <p>幂等：重复调用不会重复归还；没有领取记录时返回 false（空操作）。
     * 实现方需要自己记住"归还所需的最小字段"（例如 spuId——它不属于对外快照）。
     *
     * @return true=确实归还了（或已经归还过）；false=没有该订单的领取记录
     */
    boolean restore(Long memberId, String orderNo);
}
