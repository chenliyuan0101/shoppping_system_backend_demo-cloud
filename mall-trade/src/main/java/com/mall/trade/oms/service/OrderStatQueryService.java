package com.mall.trade.oms.service;

import com.mall.trade.common.dto.MemberOrderBriefVO;
import com.mall.trade.common.dto.OrderSummaryVO;
import com.mall.trade.common.dto.OrderTrendPointVO;
import com.mall.trade.common.dto.SpuAmountVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.mall.common.support.MemberId;

/**
 * 订单域的<b>看板统计契约</b>（供后台 / BFF 使用）。
 *
 * <p>形状遵守《微服务改造方案.md》§2.8：只收发 DTO，不含 Entity / Mapper / {@code LambdaQueryWrapper}。
 * 与 {@code StatService} 的分工：{@code StatService} 管的是<b>派生统计表</b>（{@code oms_order_daily_stat}，
 * 可重算、供历史查询）；本接口是<b>实时聚合</b>（看板/趋势/榜单，直接查源表）。
 * 两者都属订单域，因此都读得到订单表；差别只在口径与用途。
 *
 * <p>⚠️ 调用方注意：本接口是<b>读路径</b>，按 §4.6 允许并发聚合 + 缓存 + 降级；
 * 任一方法失败时后台看板应展示 0/空而不是整体 5xx。
 */
public interface OrderStatQueryService {

    /** 看板概览（今日下单/今日销售额/待发货/待处理退款）——"今天"的口径由订单域自己定义 */
    OrderSummaryVO summary();

    /**
     * 近 N 天下单数与销售额趋势。
     *
     * @param days 天数，收敛到 [1, 30]
     * @return <b>连续且补零</b>、按日期升序的序列（没有数据的日期也在，值为 0）
     */
    List<OrderTrendPointVO> trend(int days);

    /**
     * 销售额榜单：已支付订单明细按 SPU 聚合、金额倒序取前 N。
     *
     * @param limit 条数，收敛到 [1, 20]
     * @return 有序列表；商品可能已被删除，调用方自行兜底展示文案
     */
    List<SpuAmountVO> topPaidAmountBySpu(int limit);

    /**
     * 某个会员的订单口径摘要（后台会员详情用）。
     *
     * @param memberId 会员 id；为 null 时返回全 0
     */
    MemberOrderBriefVO memberOrderBrief(Long memberId);

    /**
     * <b>批量</b>会员订单口径摘要（P7 新增：后台会员列表的"一次批量补数"）。
     *
     * <p>为什么必须有它：后台会员列表（{@code mall-admin} 的 {@code /api/admin/member/page}）
     * 拿到当页 id 后需要补"订单数 / 累计实付"。若逐个会员调 {@link #memberOrderBrief(Long)}，
     * 每页就是 {@code 2 × pageSize} 次查询——规格 §4 明令禁止的 N+1；
     * 列表的可执行判据是"**远程调用次数与页码无关**"，那就必须有一个"一页一次"的批量口径。
     *
     * <p>口径与 {@link #memberOrderBrief(Long)} <b>逐字相同</b>
     * （{@code orderCount} = 该会员全部未删除订单数；{@code paidAmount} = {@code pay_status=1} 的
     * {@code pay_amount} 之和，**不含**已全额退款）。两者的等价性由用例钉住。
     *
     * @param memberIds 会员 id 集合；空/null → 空 Map（**不发 SQL**：空集合会拼出 {@code IN ()}）
     * @return 请求的**每个** id 都有一条（没有订单的会员给 {@code (0, 0)}——
     *         与 {@link #memberOrderBrief(Long)} 对"无订单会员"返回全 0 的口径一致）；
     *         列表用 {@code LinkedHashMap} 保持入参顺序，便于调用方对账
     */
    Map<Long, MemberOrderBriefVO> memberOrderBriefs(Collection<Long> memberIds);
}
