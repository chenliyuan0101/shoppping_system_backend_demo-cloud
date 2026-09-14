package com.mall.usercenter.service;

import com.mall.usercenter.support.dto.CartClaimResultVO;
import com.mall.usercenter.support.dto.CartItemSnapshotVO;

import java.util.Collection;
import java.util.List;

/**
 * 购物车<b>结算契约</b>（供下单链路使用）。
 *
 * <p>为什么不并进 {@link CartService}：那个是用户端的购物车服务（增删改查 + 展示装配），
 * 本接口只服务结算这一件事——<b>读要结算的条目</b>与<b>原子清空这些条目</b>。
 * 两道能力窄而明确，调用方拿不到"改数量/加购/查整个购物车"的权限面。
 *
 * <p>⚠️ {@link #claim} 的语义不是"删几条数据"，而是<b>结算闸门</b>：
 * 条件删除的影响行数就是并发去重凭证——两个并发请求（不同 {@code orderNo}）只有一个能把条目删掉。
 * 这是"用一条 SQL 的原子性替代分布式锁"的关键设计（《微服务改造方案.md》§4.1 步骤 ①）。
 *
 * <p><b>P3-3 搬迁后的形态</b>：原来的单阶段 {@code consumeItems} 已删除——它依赖
 * "删除动作会被调用方的事务回滚"，而这个前提在购物车搬到本服务（跨进程）后不成立：
 * 订单失败回滚时明细已经被删掉且回不来。取而代之的是两阶段
 * {@link #claim}（领取 + 记快照）+ {@link #restore}（补偿归还）。
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
     * 结算闸门（P3 形态）：以 {@code orderNo} 为<b>幂等键</b>领取这些条目。
     *
     * <p>语义：
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
