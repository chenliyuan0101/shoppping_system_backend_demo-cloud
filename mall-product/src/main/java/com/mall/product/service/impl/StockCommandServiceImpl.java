package com.mall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mall.product.support.BusinessException;
import com.mall.product.support.constant.EnableStatus;
import com.mall.product.support.constant.StockChangeType;
import com.mall.product.support.dto.StockLineVO;
import com.mall.product.domain.Sku;
import com.mall.product.domain.SkuStockLog;
import com.mall.product.domain.Spu;
import com.mall.product.mapper.SkuMapper;
import com.mall.product.mapper.SkuStockLogMapper;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.service.ProductSearchService;
import com.mall.product.service.StockCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 库存/销量写契约实现：把改造前散落在 {@code OrderServiceImpl} 与 {@code RefundServiceImpl}
 * 里的库存扣减、回补、销量累加<b>原样搬进商品域</b>，一张表只由一个域改。
 *
 * <p><b>刻意保留的细节</b>（每一条都对应一次真实事故或审计结论，改动前请先读测试）：
 * <ol>
 *   <li><b>防超卖</b>：单条 SQL {@code stock = stock - n WHERE status=1 AND stock >= n}，
 *       判定与扣减在数据库里原子完成；影响行数为 0 即库存不足。</li>
 *   <li><b>防死锁</b>：所有方法先按 {@code skuId} 升序处理——多 SKU 订单在同一事务里多次更新
 *       {@code pms_sku} 时加锁顺序一致，不会与其它订单交叉加锁。</li>
 *   <li><b>流水 before/after 取"更新后再读"</b>：同事务内能看到自己刚写入的值；
 *       若"先读后更新"，并发下会写出与相邻流水对不上的账。</li>
 *   <li><b>回补时用绕过逻辑删除的查询判断 SKU 存在</b>：后台改商品会把旧 SKU 置 {@code deleted=1}，
 *       若直接 {@code selectById} 得到 null 就跳过，历史订单的库存会<b>静默不回补</b>。</li>
 *   <li><b>索引同步</b>：库存与销量都是检索索引的字段（{@code totalStock}/{@code sales} 排序），
 *       变更后按 SPU 标记待同步（MQ 主通道 / Redis 兜底）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCommandServiceImpl implements StockCommandService {

    private final SkuMapper skuMapper;
    private final SpuMapper spuMapper;
    private final SkuStockLogMapper skuStockLogMapper;
    /** 库存/销量变化后的检索索引增量同步标记(只写 Redis 队列) */
    private final ProductSearchService productSearchService;

    @Override
    @Transactional
    public void reserve(String orderNo, List<StockLineVO> lines) {
        List<StockLineVO> sorted = sortedBySkuId(lines);
        if (sorted.isEmpty()) {
            return;
        }
        for (StockLineVO line : sorted) {
            // 单条 SQL 防超卖：条件不满足则影响行数为 0
            int affected = skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                    .setSql("stock = stock - " + line.getQuantity())
                    .eq(Sku::getId, line.getSkuId())
                    .eq(Sku::getStatus, EnableStatus.ENABLED)
                    .ge(Sku::getStock, line.getQuantity()));
            if (affected == 0) {
                throw new BusinessException(409, "商品库存不足：" + spuTitleOf(line));
            }
            // 更新之后再读：同一事务内能看到自己刚写入的值，流水里的 before/after 才是真实值
            Sku after = skuMapper.selectById(line.getSkuId());
            writeLog(orderNo, line.getSkuId(), StockChangeType.ORDER_DEDUCT, -line.getQuantity(),
                    after == null ? null : after.getStock());
        }
        productSearchService.markDirty(sorted.stream().map(StockLineVO::getSpuId).toList());
    }

    @Override
    @Transactional
    public void release(String orderNo, List<StockLineVO> lines, int changeType) {
        List<StockLineVO> sorted = sortedBySkuId(lines);
        if (sorted.isEmpty()) {
            return;
        }
        List<Long> touchedSpuIds = new ArrayList<>();
        for (StockLineVO line : sorted) {
            // 🆕 P6-5 #2 幂等闸：同一 (orderNo, changeType, skuId) 只回补一次。
            //   为什么必须有：P6-4 把 release 改成"trade 侧提交后调用"（StockReleaseAfterCommit），
            //   它解决了"本地回滚后重试重复回补"，但没解决**重复调用本身**——"提交后调用"与"对账重试"
            //   两条路径都可能把同一行回补第二次 ⇒ **库存凭空多**（超卖方向、不可逆）。
            //   方案 §4.2 原文即要求"release 以 orderNo 为键，重复调用无副作用"。
            if (alreadyLogged(orderNo, changeType, line.getSkuId())) {
                log.info("该行已回补过，跳过（幂等）: orderNo={} changeType={} skuId={}",
                        orderNo, changeType, line.getSkuId());
                continue;
            }
            Sku current = skuMapper.selectByIdIgnoreLogicDelete(line.getSkuId());
            if (current == null) {
                // 行真的没了才告警跳过（见类注释第 4 条）
                log.warn("回补库存时 SKU 已不存在(需人工核对): orderNo={} skuId={} qty={}",
                        orderNo, line.getSkuId(), line.getQuantity());
                continue;
            }
            skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                    .setSql("stock = stock + " + line.getQuantity())
                    .eq(Sku::getId, line.getSkuId()));
            // 更新之后再读：同事务内可见自己刚写入的值 → 流水的 after 是真实库存
            Sku after = skuMapper.selectByIdIgnoreLogicDelete(line.getSkuId());
            Integer afterStock = after == null || after.getStock() == null
                    ? (current.getStock() == null ? 0 : current.getStock()) + line.getQuantity()
                    : after.getStock();
            writeLog(orderNo, line.getSkuId(), changeType, line.getQuantity(), afterStock);
            touchedSpuIds.add(current.getSpuId() == null ? line.getSpuId() : current.getSpuId());
        }
        productSearchService.markDirty(touchedSpuIds);
    }

    @Override
    @Transactional
    public void incrementSales(String orderNo, List<StockLineVO> lines) {
        List<StockLineVO> sorted = sortedBySkuId(lines);
        if (sorted.isEmpty()) {
            return;
        }
        List<Long> touchedSpuIds = new ArrayList<>();
        for (StockLineVO line : sorted) {
            // 销量是无条件累加：调用方必须已经在"待收货 → 已完成"的 CAS 抢到之后才调这里
            skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                    .setSql("sales = sales + " + line.getQuantity())
                    .eq(Sku::getId, line.getSkuId()));
            Long spuId = line.getSpuId();
            if (spuId != null) {
                spuMapper.update(null, new LambdaUpdateWrapper<Spu>()
                        .setSql("sales = sales + " + line.getQuantity())
                        .eq(Spu::getId, spuId));
                touchedSpuIds.add(spuId);
            }
        }
        // 销量是索引的排序字段(default 排序) → 标记待同步
        productSearchService.markDirty(touchedSpuIds);
    }

    /** 按 skuId 升序：保证多 SKU 事务的加锁顺序一致（见类注释第 2 条） */
    private static List<StockLineVO> sortedBySkuId(List<StockLineVO> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .sorted(Comparator.comparing(StockLineVO::getSkuId))
                .toList();
    }

    /** 库存不足时的提示要带商品标题——按需查一次（只在失败路径上发生） */
    private String spuTitleOf(StockLineVO line) {
        Spu spu = line.getSpuId() == null ? null : spuMapper.selectById(line.getSpuId());
        return spu == null ? "" : spu.getTitle();
    }

    /**
     * 幂等判据（P6-5 #2）：同一 {@code (orderNo, changeType, skuId)} 是否**已经有**流水。
     *
     * <p><b>为什么必须是"同事务内 + FOR UPDATE"</b>：这是典型的 check-then-act——若读到 0 之后、
     * 写流水之前被另一个并发调用插入，两边都会回补（库存凭空多）。`FOR UPDATE` 在"行不存在"时取
     * <b>间隙锁</b>，把并发同键调用串起来；表上另有唯一索引 {@code uk_order_type_sku} 作最后一道防线
     * （见 {@code db/04-uk-order-type-sku.sql}）：万一判据被绕过，重复插入会直接报 1062，而不是静默多回补。
     *
     * <p>⚠️ {@code orderNo} 为空（手工调整 {@code MANUAL_ADJUST}）时**不做**幂等收敛：
     * 那种流水没有业务键（可能是同一 SKU 的多次人工调整），语义上不要求唯一。
     */
    private boolean alreadyLogged(String orderNo, int changeType, Long skuId) {
        if (orderNo == null || orderNo.isBlank() || skuId == null) {
            return false;
        }
        return !skuStockLogMapper.selectList(new LambdaQueryWrapper<SkuStockLog>()
                .eq(SkuStockLog::getOrderNo, orderNo)
                .eq(SkuStockLog::getChangeType, changeType)
                .eq(SkuStockLog::getSkuId, skuId)
                .last("FOR UPDATE")).isEmpty();
    }

    private void writeLog(String orderNo, Long skuId, int type, int delta, Integer afterStock) {
        SkuStockLog log = new SkuStockLog();
        log.setSkuId(skuId);
        log.setOrderNo(orderNo);
        log.setChangeType(type);
        log.setDelta(delta);
        log.setAfterStock(afterStock);
        // before = after - delta（扣减时 delta 为负，所以 before 更大）
        log.setBeforeStock(afterStock == null ? null : afterStock - delta);
        log.setRemark(remarkOf(type));
        skuStockLogMapper.insert(log);
    }

    /** 流水文案与改造前逐字一致（订单侧三型 + 退款侧一型） */
    private static String remarkOf(int type) {
        return switch (type) {
            case StockChangeType.ORDER_DEDUCT -> "下单扣减";
            case StockChangeType.TIMEOUT_RESTORE -> "超时关单回补";
            case StockChangeType.REFUND_RESTORE -> "退款回补";
            default -> "取消回补";
        };
    }
}
