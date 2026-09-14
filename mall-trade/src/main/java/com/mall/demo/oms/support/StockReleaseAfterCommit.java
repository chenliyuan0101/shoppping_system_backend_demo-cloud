package com.mall.demo.oms.support;

import com.mall.demo.common.dto.StockLineVO;
import com.mall.demo.pms.service.StockCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * <b>库存回补统一在"事务提交后"执行</b>（P6-4 / D1 的取舍，2026-09-14 定稿）。
 *
 * <h2>为什么必须移出事务（这条是正确性，不是风格）</h2>
 * 取消/超时/后台关闭/退款这些路径的防重闸门都是**本地库的 CAS**
 * （例如退款的 {@code pay_status != REFUNDED → REFUNDED}）。
 * P6-4 之后 {@code release} 是**跨进程**调用（{@code mall-product}），本地事务回滚**覆盖不到**它：
 * <pre>
 *   事务内 release(已生效) → 之后任何一步抛异常 → 本地回滚(CAS 与支付状态都撤销) → 库存却已经加回
 *   → 用户重试审核 → CAS 再次成功 → **再回补一次** → 库存凭空多出来（超卖方向、不可逆、"看起来一切正常"）
 * </pre>
 * 移到<b>提交之后</b>：CAS 先提交 ⇒ 重试被 CAS 挡住 ⇒ <b>双回补在结构上不可能</b>；
 * 反过来，提交后进程掉掉只是"少回补" —— 那是**安全方向**（库存偏少不会超卖），
 * 而且订单状态已置为已退款/已取消却没有对应的 {@code REFUND_RESTORE}/{@code CANCEL_RESTORE} 流水
 * ⇒ <b>可被 P8 每日对账发现</b>。
 *
 * <p><b>一句话原则：宁可少回补可对账，绝不能凭空多。</b>
 *
 * <h2>为什么是一个共享组件（而不是各处各写一遍）</h2>
 * 原有的 {@code registerSynchronization} 写法散落在下单链路里，而 {@code release} 有 4 个入口
 * （取消 / 超时关单 / 后台关闭 / 退款）。"一条规则、一个机制"才不会出现"哪条路径是哪种语义"的漂移；
 * 机制本身照抄既有的 {@code publishAfterCommit}（同一个 {@code TransactionSynchronizationManager}，
 * <b>不引入新框架</b>）。
 *
 * <h2>⚠️ 残留（(b) 才是根治，归 P6-5）</h2>
 * 本类解决的是"重复回补"，**没有**解决"重复调用本身"：{@code release} 目前**不幂等**
 * （重复调用会重复加回）。根治方案是给 product 的 {@code release} 加
 * {@code (orderNo, changeType, skuId)} 幂等闸（方案 §4.2 原文就要求"以 {@code orderNo} 为键，重复调用无副作用"），
 * 之后 P8 的"失败重试 + 对账自动修"才站得住。<b>已记入 P6-5 待办，不是口头承诺。</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReleaseAfterCommit {

    private final StockCommandService stockCommandService;

    /**
     * 登记"事务提交后回补库存"。
     *
     * <p>三种调用处境都要有确定行为：
     * <ol>
     *   <li><b>在事务里</b> ⇒ 注册 {@code afterCommit} 回调（Spring 保证它在**提交后、请求返回前同步执行**
     *       ⇒ "取消/审核之后立刻查库存"的用例仍然成立）；</li>
     *   <li><b>不在事务里</b> ⇒ <b>立刻执行</b>。⚠️ 这一条不能省：若照搬"回滚回调"那种
     *       {@code isSynchronizationActive()==false ⇒ 只 warn 不执行}的写法，非事务路径会**静默不回补**
     *       （库存永久偏少且没人知道）—— 语义完全不同，别混用；</li>
     *   <li>空行集合 ⇒ 直接返回（不发无意义的远程调用）。</li>
     * </ol>
     */
    public void release(String orderNo, List<StockLineVO> lines, int changeType) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        // 快照：回调在事务结束后执行，不能依赖调用方随后是否改动这个集合
        List<StockLineVO> snapshot = List.copyOf(lines);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务：立刻执行（见 javadoc 第 2 条，绝不能静默跳过）
            doRelease(orderNo, snapshot, changeType);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doRelease(orderNo, snapshot, changeType);
            }
        });
    }

    /**
     * 真正的回补调用：<b>失败只记 ERROR、绝不抛出</b>。
     *
     * <p>事务已经提交，抛出去只会把"成功"报成"失败"（用户会去重试，而重试会被 CAS 挡住 ⇒ 白折腾）；
     * 日志里带齐 {@code orderNo + changeType + 恢复数量}，P8 对账据此发现"状态已终态但没有回补流水"的单子。
     */
    private void doRelease(String orderNo, List<StockLineVO> snapshot, int changeType) {
        try {
            stockCommandService.release(orderNo, snapshot, changeType);
            log.info("库存已回补(提交后): orderNo={} changeType={} 恢复 {} 个 SKU", orderNo, changeType, snapshot.size());
        } catch (Exception e) {
            log.error("库存回补失败(事务已提交，需 P8 对账: 查订单 {} 状态已终态但无 changeType={} 的库存流水；应恢复 {} 个 SKU): {}",
                    orderNo, changeType, snapshot.size(), e.getMessage(), e);
        }
    }
}
