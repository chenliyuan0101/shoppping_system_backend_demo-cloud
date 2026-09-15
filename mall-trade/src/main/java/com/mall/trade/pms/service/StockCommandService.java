package com.mall.trade.pms.service;

import com.mall.trade.common.dto.StockLineVO;

import java.util.List;

/**
 * 商品域的<b>库存/销量写契约</b>（供交易链路使用）。
 *
 * <p>名字里强调 "Command"：与只读的 {@link ProductQueryService} 分开——
 * 这个接口的每个方法都会改 {@code pms_sku} / {@code pms_spu} / 写 {@code pms_sku_stock_log}，
 * 是 §1.3 里最严重的三处跨域写（#1~#3）的唯一入口。分开后，"谁需要改库存"是显式的。
 *
 * <p><b>这是 P0 里唯一涉及分布式事务设计的边界</b>（方案 §4.1 / §4.2）。P0 阶段仍同进程调用，
 * 但契约形状与语义必须能原样变成 HTTP 端点：入参只用 {@link StockLineVO}，
 * 以 {@code orderNo} 作为业务幂等键，调用方按 {@code skuId} 升序传入（实现也会再排一次序）。
 *
 * <p>⚠️ 实现里保留了改造前的全部"防超卖/防死锁/防对账不平"细节，见各方法注释；
 * 改动这些细节前请先读 {@code ConcurrentStockMySqlTest}。
 */
public interface StockCommandService {

    /**
     * 预占库存（当前语义即"实扣"）：单条 SQL 扣减 + 写库存流水。
     *
     * <p>与 {@link #release} 成对使用：下单成功后预占，取消/超时/关闭/退款时回补。
     *
     * @param orderNo 订单号（业务幂等键，同时回填到库存流水）
     * @param lines   待扣减的行；实现会按 {@code skuId} 升序处理
     * @throws com.mall.trade.common.BusinessException 409 库存不足（文案含商品标题，与改造前一致）
     */
    void reserve(String orderNo, List<StockLineVO> lines);

    /**
     * 回补库存：把 {@code lines} 的数量加回，并写一条对应 {@code changeType} 的流水。
     *
     * <p>用于取消、超时关单、后台关闭、售后退款四条路径。
     *
     * @param orderNo    订单号
     * @param lines      待回补的行；实现会按 {@code skuId} 升序处理
     * @param changeType 变更类型，取值见 {@code common.constant.StockChangeType}
     *                   （同时决定流水里的 remark 文案与正负号）
     */
    void release(String orderNo, List<StockLineVO> lines, int changeType);

    /**
     * 累加销量（展示口径）：SKU 与 SPU 的 {@code sales} 同时加。
     *
     * <p>只在"确认收货"的 CAS 抢到之后调用——销量是无条件累加，重复调用会重复累加，
     * 前台按销量排序会立刻被污染。{@code orderNo} 当前仅用于可追溯（将来做幂等去重的键）。
     *
     * @param orderNo 订单号
     * @param lines   待累加的行（必须带 {@code spuId}，否则 SPU 销量无法更新）
     */
    void incrementSales(String orderNo, List<StockLineVO> lines);
}
