package com.mall.trade.oms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mall.trade.common.BusinessException;
import com.mall.trade.common.RequestValidator;
import com.mall.common.support.PageKit;
import com.mall.common.support.JsonKit;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.constant.EnableStatus;
import com.mall.trade.oms.domain.OmsRefund;
import com.mall.trade.oms.domain.Order;
import com.mall.trade.oms.domain.OrderItem;
import com.mall.trade.oms.domain.Payment;
import com.mall.trade.oms.dto.AddressInfoVO;
import com.mall.trade.oms.dto.CloseOrderRequest;
import com.mall.trade.oms.dto.OrderCreateRequest;
import com.mall.trade.oms.dto.OrderDetailVO;
import com.mall.trade.oms.dto.OrderListVO;
import com.mall.trade.oms.dto.PayMockRequest;
import com.mall.trade.oms.dto.PreviewItemVO;
import com.mall.trade.oms.dto.PreviewResult;
import com.mall.trade.oms.dto.ShipRequest;
import com.mall.trade.oms.mapper.OrderItemMapper;
import com.mall.trade.oms.mapper.OrderMapper;
import com.mall.trade.oms.mapper.PaymentMapper;
import com.mall.trade.oms.mapper.RefundMapper;
import com.mall.trade.oms.mq.OrderClosedMessage;
import com.mall.trade.oms.mq.OrderEventMessage;
import com.mall.trade.common.MqTopology;
import com.mall.trade.oms.mq.OrderEventPublisher;
import com.mall.trade.oms.mq.OrderFinishedMessage;
import com.mall.trade.oms.mq.OrderTimeoutPublisher;
import com.mall.trade.oms.service.OrderService;
import com.mall.trade.oms.service.RefundService;
import com.mall.trade.common.constant.OrderPayStatus;
import com.mall.trade.oms.support.OrderSource;
import com.mall.trade.common.constant.OrderStatus;
import com.mall.trade.oms.support.OrderTexts;
import com.mall.trade.oms.support.PaymentStatus;
import com.mall.trade.oms.support.RefundTexts;
import com.mall.trade.common.constant.StockChangeType;
import com.mall.trade.common.dto.SkuSnapshotVO;
import com.mall.trade.common.dto.SpuSnapshotVO;
import com.mall.trade.common.dto.StockLineVO;
import com.mall.trade.pms.service.ProductQueryService;
import com.mall.trade.pms.service.StockCommandService;
import com.mall.trade.common.contract.CouponCommandService;
import com.mall.trade.common.contract.CouponQueryService;
import com.mall.trade.common.dto.AddressSnapshotVO;
import com.mall.trade.common.dto.CartClaimResultVO;
import com.mall.trade.common.dto.CartItemSnapshotVO;
import com.mall.trade.common.contract.AddressQueryService;
import com.mall.trade.common.contract.CartCheckoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.mall.trade.oms.support.OrderNoGenerator;
import com.mall.common.support.MemberId;

/**
 * 订单实现要点：
 *  - 价格一律以服务端当前 SKU 价核算(防篡改)；订单/明细/地址落快照；
 *  - 下单事务内 `stock=stock-n WHERE stock>=n` 预占(即实扣)，取消/超时回补并写库存流水；
 *  - 支付流水 uk(order_no) 一单一笔，天然防重复发货。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentMapper paymentMapper;
    /** 购物车域的结算契约（P0 边界冻结：不再直连 ums_cart_item） */
    private final CartCheckoutService cartCheckoutService;
    /** 会员域的地址契约（P0 边界冻结：不再直连 ums_address） */
    private final AddressQueryService addressQueryService;
    /** 商品域的只读契约（P0 最后一批：下单时取 SKU/SPU 快照，不再直连 pms_sku/pms_spu） */
    private final ProductQueryService productQueryService;
    /** 商品域的库存写契约（P0 最后一批：扣减/回补/销量都不再由交易域直接改表） */
    private final StockCommandService stockCommandService;
    /** P6-4（D1）：库存回补统一在**事务提交后**执行（跨进程 release 不可被本地回滚，见该类注释） */
    private final com.mall.trade.oms.support.StockReleaseAfterCommit stockReleaseAfterCommit;
    /** 营销域的券契约（P0 边界冻结：券的规则与核销都不再由交易域自己实现） */
    private final CouponQueryService couponQueryService;
    private final CouponCommandService couponCommandService;
    private final RefundMapper refundMapper;
    private final RefundService refundService;
    /** 超时关单的延迟消息投递(事务提交后发送；投递失败 fail-open，由兜底扫描补偿) */
    private final OrderTimeoutPublisher orderTimeoutPublisher;
    /** 领域事件投递(支付成功/已发货/退款到账 → 站内消息 + 统计) */
    private final OrderEventPublisher orderEventPublisher;
    /** 请求参数校验(约束注解写在 DTO 字段上，此处统一触发) */
    private final RequestValidator requestValidator;

    @Value("${mall.order.pay-timeout-minutes:30}")
    private long payTimeoutMinutes;

    // ==================== 预览 ====================

    @Override
    @Transactional(readOnly = true)
    public PreviewResult preview(Long memberId, OrderCreateRequest request) {
        List<PurchaseLine> lines = resolveLines(memberId, request, false);

        long total = 0;
        List<PreviewItemVO> items = new ArrayList<>();
        for (PurchaseLine line : lines) {
            total += line.price() * line.quantity();
            PreviewItemVO vo = new PreviewItemVO();
            vo.setSkuId(line.sku().getId());
            vo.setSpuId(line.spu().getId());
            vo.setTitle(line.spu().getTitle());
            vo.setSkuName(JsonKit.toSpecText(line.sku().getSpecValues()));
            vo.setImage(line.sku().getImage() != null ? line.sku().getImage() : line.spu().getMainImage());
            vo.setPrice(line.price());
            vo.setQuantity(line.quantity());
            int stock = line.sku().getStock() == null ? 0 : line.sku().getStock();
            vo.setStockEnough(stock >= line.quantity());
            items.add(vo);
        }

        AddressSnapshotVO address = addressQueryService.defaultAddress(memberId);
        long discount = couponQueryService.discountFor(memberId, request.getCouponId(), total);
        return PreviewResult.builder()
                .items(items)
                .defaultAddress(address == null ? null : toAddressInfo(address))
                .coupons(couponQueryService.usableCoupons(memberId, total))
                .freightAmount(0L)
                .discountAmount(discount)
                .totalAmount(total)
                .payAmount(total - discount)
                .build();
    }

    /**
     * 注册"事务回滚 → 归还购物车明细"的回调（P3 两阶段闸门的第二阶段）。
     *
     * <p>为什么必须有它：闸门的领取动作在 user-center 那边（跨进程），本进程的事务回滚
     * <b>管不到</b>它——库存不足、券失效、落单失败时若不显式归还，用户会发现"下单失败、
     * 购物车也空了"。改造前这个保证是靠"同一个本地事务"白拿的，拆服务后必须自己写。
     *
     * <p>只在<b>回滚</b>时归还：提交成功就意味着明细真的被结算掉了。
     * 归还失败不抛异常（见 UserCenterClient.restoreCartItems 的注释），由对账兜底。
     */
    private void registerCartRestoreOnRollback(Long memberId, String orderNo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("事务同步未激活，购物车归还只能靠对账兜底: memberId={} orderNo={}", memberId, orderNo);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    cartCheckoutService.restore(memberId, orderNo);
                }
            }
        });
    }

    /**
     * 注册"事务回滚 → 解锁券"的回调（P5 步骤 C：券锁定的补偿）。
     *
     * <p><b>为什么必须有它</b>：{@code lock} 是在**营销域**（跨进程）执行的 CAS
     * （{@code UNUSED → LOCKED} + 写 {@code order_no}），本进程的事务回滚**管不到**它。
     * 下单在 lock 之后还可能失败（落单失败、后续步骤抛异常），少了这个回调就会出现
     * "下单失败、券却卡在 LOCKED"——对用户来说就是"这张券既用不了、也看不见"，
     * 而且旧实现（本地表 CAS）本来是靠同一个事务白拿这个保证的。
     *
     * <p>只在**回滚**分支解锁；提交成功意味着订单真的用掉了这张券（步骤 E 会在支付时 use）。
     *
     * <p>{@code unlock} 走 {@code MarketingClient#unlockQuietly}：**不抛异常**。
     * 回滚阶段再抛只会掩盖原始失败原因（用户看到的会变成第二个错误），
     * 而且那时抛也改不了任何结果；失败留 error 日志，由步骤 E 的每日对账兜底。
     * 这与 {@link #registerCartRestoreOnRollback} 是同一个口径（照它的写法）。
     */
    private void registerCouponUnlockOnRollback(Long memberId, Long couponMemberId, String orderNo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("事务同步未激活，券解锁只能靠对账兜底: memberId={} couponMemberId={} orderNo={}",
                    memberId, couponMemberId, orderNo);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    couponCommandService.unlock(memberId, couponMemberId, orderNo);
                }
            }
        });
    }

    /**
     * 注册"事务回滚 → 反向回补库存"的回调（<b>P6-4 §4.2-7，本批最容易漏、后果最重的一条</b>）。
     *
     * <p><b>为什么必须有它</b>：P6-4 之后 {@code reserve} 是在**商品域**（跨进程）执行的
     * （{@code mall-product} 的 {@code POST /internal/v1/stock/reserve}），本地事务回滚**覆盖不到**它。
     * 而 {@code reserve} 之后本方法还可能失败：
     * <ul>
     *   <li>券 CAS 抢不到 ⇒ 抛 {@code 409 优惠券已被使用或失效}；</li>
     *   <li>{@code orderMapper.insert} / 明细写入失败 ⇒ 抛异常。</li>
     * </ul>
     * 少了这个回调，这些路径都会变成 <b>永久库存泄漏</b>：库存已经扣掉、却没有订单行——
     * 连"超时关单任务"都兜不住（它是按订单行扫的，而这里根本没有订单）。
     * 改造前这个保证是靠"同一个本地事务"白拿的，拆服务后必须自己写。
     *
     * <p><b>只在回滚分支补偿</b>：提交成功意味着库存真的被这单占用了（支付/关单链路负责后续）。
     *
     * <p><b>{@code changeType} 为什么选 {@link StockChangeType#CANCEL_RESTORE}</b>：
     * 既有常量只有 5 个（{@code ORDER_DEDUCT=1 / CANCEL_RESTORE=2 / TIMEOUT_RESTORE=3 /
     * REFUND_RESTORE=4 / MANUAL_ADJUST=5}），本场景是"订单没有成立、把预占还回去"，
     * 语义上最贴近"取消回补"（{@code TIMEOUT_RESTORE} 是超时关单专用、{@code REFUND_RESTORE} 是售后回补）。
     * **不新造未定义值**——流水口径要能被现有对账脚本读懂。
     *
     * <p><b>补偿失败绝不抛出</b>（与 {@link #registerCartRestoreOnRollback} /
     * {@link #registerCouponUnlockOnRollback} 同一口径）：回滚阶段再抛异常会**掩盖原始失败原因**，
     * 而且那时抛也改不了任何结果。失败只留 {@code ERROR} 日志，由 <b>P8 的每日对账</b>兜底
     * （判据：{@code mall_product.pms_sku_stock_log} 里存在 {@code order_no} 找不到对应订单的扣减流水）。
     *
     * <p>⚠️ <b>已知且记录在案的残留风险</b>（不给假保证）：
     * <ol>
     *   <li>进程在"reserve 成功"与"本地提交"之间**崩溃**（不是回滚）⇒ 回调不会执行 ⇒ 悬挂扣减，
     *       同样靠 P8 对账发现（这一条任何跨进程事务都躲不掉，2PC 才能根治）；</li>
     *   <li>{@link #confirm} 链路里的 {@code incrementSales} **没有逆操作**：它所在的事务若回滚，
     *       销量会多算。销量是展示口径（不参与库存/金额），且确认收货由状态 CAS 保证只成功一次，
     *       故本批不为它设计补偿——写在这里是为了"别假装它不存在"。</li>
     * </ol>
     */
    private void registerStockReleaseOnRollback(String orderNo, List<StockLineVO> stockLines) {
        if (stockLines == null || stockLines.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("事务同步未激活，库存回补只能靠对账兜底: orderNo={} lines={}", orderNo, stockLines.size());
            return;
        }
        // 复制一份不可变快照：回调在事务结束后才执行，不能依赖调用方后续是否改动这个集合
        List<StockLineVO> snapshot = List.copyOf(stockLines);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    stockCommandService.release(orderNo, snapshot, StockChangeType.CANCEL_RESTORE);
                    log.warn("订单事务回滚，已反向回补预占库存: orderNo={} lines={} changeType={}",
                            orderNo, snapshot.size(), StockChangeType.CANCEL_RESTORE);
                } catch (Exception e) {
                    // 绝不抛：回滚阶段抛异常会掩盖原始失败原因。留 ERROR + 指向对账。
                    // ⚠️ P6-6：表名写**全限定**（`mall_product.pms_sku_stock_log`）——商品真值在 P6-4 之后
                    //    已搬到 mall_product，`mall.pms_sku_stock_log` 已删除；运维照旧文案去 `mall` 库找会扑空。
                    log.error("库存回补失败(需 P8 每日对账兜底: 查 mall_product.pms_sku_stock_log 中 order_no={} 的扣减无对应订单): {}",
                            orderNo, e.getMessage(), e);
                }
            }
        });
    }

    /**
     * 关单的"券侧收尾"（P5 步骤 E）：**同步解锁 + 事务提交后补发 {@code order.closed}**。
     *
     * <p>三个关单入口（会员取消 / 超时关单 / 后台关单的两个互斥分支）全部走这一个方法，
     * 理由很直白：**漏掉任何一个入口，就等于留下一条"券永远锁着"的路径**，
     * 而那种券对用户来说"既用不了也看不见"（对外投影成已使用）。集中成一处，就不会漏。
     *
     * <p>两层保险：
     * <ol>
     *   <li><b>同步</b> {@code unlock}：立刻生效，失败只记日志（它是跨进程调用，不能让它把关单搞失败）；</li>
     *   <li><b>异步</b> {@code order.closed}：事务提交后投递，marketing 消费后幂等地再解一次。
     *       覆盖"同步调用失败""本进程提交前后挂掉"这类情况。第三层是营销域每日对账。</li>
     * </ol>
     *
     * <p>⚠️ 没用券的订单（{@code couponId == null}）**不发同步调用**（省一次远程往返、
     * 也避免把"券服务不可用"变成"关单不可用"），但仍然发事件（保持"每次关单都有事件"的简单语义，
     * 消费者见 {@code couponMemberId == null} 直接 ack）。
     */
    private void releaseCouponOnClose(Order order, String reason) {
        if (order.getCouponId() != null) {
            try {
                boolean unlocked = couponCommandService.unlock(
                        order.getMemberId(), order.getCouponId(), order.getOrderNo());
                if (!unlocked) {
                    // 不是本单锁的券 / 已被核销 —— 都属"不需要解锁"，但值得留痕（对账会再核一遍）
                    log.warn("关单时券解锁未生效(交给 order.closed 兜底与每日对账): orderNo={} couponMemberId={} reason={}",
                            order.getOrderNo(), order.getCouponId(), reason);
                }
            } catch (Exception e) {
                log.error("关单时券解锁调用异常(交给兜底): orderNo={} couponMemberId={} reason={}",
                        order.getOrderNo(), order.getCouponId(), reason, e);
            }
        }
        orderEventPublisher.publishRawAfterCommit(
                MqTopology.EVENT_ROUTING_CLOSED,
                order.getOrderNo(),
                new OrderClosedMessage(order.getOrderNo(), order.getMemberId(), order.getCouponId(),
                        reason, System.currentTimeMillis()));
    }

    // ==================== 创建订单 ====================

    @Override
    @Transactional
    public String createOrder(Long memberId, OrderCreateRequest request) {
        List<PurchaseLine> lines = resolveLines(memberId, request, true);
        AddressSnapshotVO address = requireAddress(memberId, request.getAddressId());
        String orderNo = OrderNoGenerator.next();

        // 0) 购物车结算的"原子闸门"：把条目清空当成"这次结算被消费掉"的证明，
        //    并且必须在扣库存**之前**做——否则不带幂等键的双击提交会各扣一遍库存、各落一单。
        //
        //    P3 起改成**两阶段**（claim + restore，见 §5 P3 的闸门口径）：
        //    · claim 以 orderNo 为幂等键：双击提交的第二次是"已由本单领取"（幂等成功），
        //      不再被误判成 409；不同订单抢同一批明细仍然只有一方拿到；
        //    · 领取之后如果本事务回滚（库存不足/券失效/落单失败），条目**不会**自动回来
        //      ——远程/跨服务时事务回滚覆盖不到它——因此注册一个回滚回调显式调用 restore。
        if ("CART".equalsIgnoreCase(request.getSource()) && request.getCartItemIds() != null
                && !request.getCartItemIds().isEmpty()) {
            CartClaimResultVO claim = cartCheckoutService.claim(memberId, orderNo, request.getCartItemIds());
            if (!claim.claimed()) {
                throw new BusinessException(409, "购物车已结算，请勿重复提交");
            }
            registerCartRestoreOnRollback(memberId, orderNo);
        }

        // 1) 预占库存：交给商品域（单条 SQL 防超卖 + 按 skuId 升序加锁 + 写库存流水），
        //    交易域只表达"这单要扣这些 SKU 的库存"。
        List<StockLineVO> stockLines = lines.stream()
                .map(line -> new StockLineVO(line.sku().getId(), line.spu().getId(), line.quantity()))
                .toList();
        stockCommandService.reserve(orderNo, stockLines);
        // P6-4 §4.2-7：reserve 现在是**跨进程**执行（mall-product），本地回滚覆盖不到它
        // ⇒ 立刻注册反向补偿。少了这一行，券 CAS 失败/落单失败都会变成**永久库存泄漏**
        // （库存已扣、没有订单行，连超时关单任务都扫不到）。见 registerStockReleaseOnRollback 的注释。
        registerStockReleaseOnRollback(orderNo, stockLines);
        long total = lines.stream().mapToLong(line -> line.price() * line.quantity()).sum();

        // 1.5) 优惠券：抵扣试算 + **锁定**（校验+占用，失败整体回滚含库存）
        //
        //      P5 步骤 C 起券的读写都在营销域（跨进程）：
        //        · discount：算钱（5 项校验在这里做，文案由营销域抛、本层原样透传）；
        //        · lock    ：CAS UNUSED(0) → LOCKED(3) 并记 order_no。抢不到 = 409「优惠券已被使用或失效」；
        //        · **本步刻意不调 use**：券停在 LOCKED(3)，而对外投影把 3 显示成"已使用"，
        //          所以用户可观测行为与改造前（下单即核销）完全一致；
        //          "支付时 use / 取消超时 unlock / 事件兜底 / 每日对账"属步骤 E。
        //
        //      ⚠️ 锁定的补偿是**显式**的：跨进程后本事务回滚**管不到**营销域已经写下的锁定，
        //      因此锁成功后立刻注册回滚回调去 unlock（见 registerCouponUnlockOnRollback）。
        long discount = 0L;
        if (request.getCouponId() != null) {
            discount = couponQueryService.discountFor(memberId, request.getCouponId(), total);
            if (!couponCommandService.lock(memberId, request.getCouponId(), orderNo)) {
                // 文案不变：与改造前抢不到核销时逐字相同（C1）
                throw new BusinessException(409, "优惠券已被使用或失效");
            }
            registerCouponUnlockOnRollback(memberId, request.getCouponId(), orderNo);
        }

        // 2) 订单+明细快照
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setMemberId(memberId);
        order.setOrderStatus(OrderStatus.WAIT_PAY);
        order.setPayStatus(OrderPayStatus.UNPAID);
        order.setPayChannel("MOCK");
        order.setSource("CART".equalsIgnoreCase(request.getSource()) ? OrderSource.CART : OrderSource.BUY_NOW);
        order.setTotalAmount(total);
        order.setFreightAmount(0L);
        order.setDiscountAmount(discount);
        order.setPayAmount(total - discount);
        order.setCouponId(request.getCouponId());
        order.setUserRemark(request.getUserRemark());
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverFullAddress(fullAddress(address));
        order.setPayExpireTime(LocalDateTime.now().plusMinutes(payTimeoutMinutes));
        orderMapper.insert(order);

        for (PurchaseLine line : lines) {
            OrderItem item = new OrderItem();
            item.setOrderNo(orderNo);
            item.setSpuId(line.spu().getId());
            item.setSkuId(line.sku().getId());
            item.setSpuTitle(line.spu().getTitle());
            item.setSkuName(JsonKit.toSpecText(line.sku().getSpecValues()));
            item.setSkuImage(line.sku().getImage() != null ? line.sku().getImage() : line.spu().getMainImage());
            item.setPrice(line.price());
            item.setQuantity(line.quantity());
            item.setTotalAmount(line.price() * line.quantity());
            // P4 批次 3：**不再写 oms_order_item.comment_status**。评价的属主已经搬到 mall-review，
            // "这条明细评价过没有"由 review 自己的 review_pending_item 表达；订单域再写一列
            // 就等于同一事实有两个属主（而且它从来没有被 read 回业务决策）。
            // 列本身保留（不删列、不改表），实体字段也保留：DB 的 DEFAULT 0 让老行为无差别。
            orderItemMapper.insert(item);
        }

        // 3) 购物车条目已在步骤 0 作为"结算闸门"清空（此处不再重复删除）

        // 4) 挂一条"到期关单"的延迟消息(事务提交后才真正投递；失败不影响下单，见 OrderTimeoutPublisher)
        orderTimeoutPublisher.publishAfterCommit(orderNo, order.getPayExpireTime());
        return orderNo;
    }

    // ==================== 优惠券抵扣 ====================
    // 券的校验规则（本人/未用/未过期/模板启用/在有效窗内/满足门槛）与抵扣封顶
    // 都已收归营销域：见 sms.service.CouponQueryService#discountFor 与 #usableCoupons。
    // 交易域只表达"我要用这张券"，不再自己判规则。

    // ==================== 查询 ====================

    @Override
    @Transactional(readOnly = true)
    public PageResult<OrderListVO> page(Long memberId, Integer status, Boolean afterSale,
                                        long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<Order> base = new LambdaQueryWrapper<Order>()
                .eq(Order::getMemberId, memberId)
                .eq(status != null, Order::getOrderStatus, status);
        if (Boolean.TRUE.equals(afterSale)) {
            // 「退款/售后」标签页：收录所有存在售后单的订单(处理中/已退款/被拒/已撤销)
            List<String> refundOrderNos = refundMapper.selectList(new LambdaQueryWrapper<OmsRefund>()
                            .eq(OmsRefund::getMemberId, memberId))
                    .stream().map(OmsRefund::getOrderNo).distinct().toList();
            if (refundOrderNos.isEmpty()) {
                return PageResult.of(0, page, size, List.of());
            }
            base.in(Order::getOrderNo, refundOrderNos);
        }
        long total = orderMapper.selectCount(base);
        List<Order> orders = orderMapper.selectList(base
                .orderByDesc(Order::getCreateTime)
                .last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, toListVos(orders));
    }

    /** 批量组装列表项：明细与售后各一次查询，避免逐单查询(N+1) */
    private List<OrderListVO> toListVos(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<String> orderNos = orders.stream().map(Order::getOrderNo).toList();
        Map<String, List<OrderItem>> itemsByOrder = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderNo, orderNos)
                                .orderByAsc(OrderItem::getId))
                .stream().collect(Collectors.groupingBy(OrderItem::getOrderNo));
        Map<String, OmsRefund> refundByOrder = refundMapper.selectList(
                        new LambdaQueryWrapper<OmsRefund>().in(OmsRefund::getOrderNo, orderNos)
                                .orderByAsc(OmsRefund::getId))
                .stream().collect(Collectors.toMap(OmsRefund::getOrderNo, r -> r, (a, b) -> b));

        return orders.stream().map(order -> {
            List<OrderItem> items = itemsByOrder.getOrDefault(order.getOrderNo(), List.of());
            OrderListVO vo = new OrderListVO();
            vo.setOrderNo(order.getOrderNo());
            vo.setOrderStatus(order.getOrderStatus());
            vo.setPayStatus(order.getPayStatus());
            vo.setPayAmount(order.getPayAmount());
            vo.setItemCount(items.stream().mapToInt(OrderItem::getQuantity).sum());
            vo.setStatusText(OrderTexts.orderStatus(order.getOrderStatus()));
            vo.setCreateTime(order.getCreateTime());
            vo.setPayExpireTime(order.getPayExpireTime());
            if (!items.isEmpty()) {
                vo.setFirstTitle(items.get(0).getSpuTitle());
                vo.setFirstImage(items.get(0).getSkuImage());
            }
            OmsRefund refund = refundByOrder.get(order.getOrderNo());
            if (refund != null) {
                vo.setRefundNo(refund.getRefundNo());
                vo.setRefundType(refund.getRefundType());
                vo.setRefundTypeText(RefundTexts.type(refund.getRefundType()));
                vo.setRefundStatus(refund.getStatus());
                vo.setRefundStatusText(RefundTexts.status(refund.getStatus()));
            }
            return vo;
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailVO detail(Long memberId, String orderNo) {
        Order order = requireOwned(memberId, orderNo);
        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderStatus(order.getOrderStatus());
        vo.setPayStatus(order.getPayStatus());
        vo.setPayChannel(order.getPayChannel());
        vo.setSourceText(order.getSource() != null && order.getSource() == OrderSource.BUY_NOW ? "立即购买" : "购物车结算");
        vo.setTotalAmount(order.getTotalAmount());
        vo.setFreightAmount(order.getFreightAmount());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setUserRemark(order.getUserRemark());
        vo.setStatusText(OrderTexts.orderStatus(order.getOrderStatus()));
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setFinishTime(order.getFinishTime());
        vo.setCreateTime(order.getCreateTime());

        AddressInfoVO address = new AddressInfoVO();
        address.setReceiverName(order.getReceiverName());
        address.setReceiverPhone(order.getReceiverPhone());
        address.setFullAddress(order.getReceiverFullAddress());
        vo.setAddress(address);

        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, orderNo).orderByAsc(OrderItem::getId));
        vo.setItems(items.stream().map(i -> {
            OrderDetailVO.OrderItemSnapshotVO s = new OrderDetailVO.OrderItemSnapshotVO();
            s.setOrderItemId(i.getId());
            s.setSpuId(i.getSpuId());
            s.setSkuId(i.getSkuId());
            s.setTitle(i.getSpuTitle());
            s.setSkuName(i.getSkuName());
            s.setImage(i.getSkuImage());
            s.setPrice(i.getPrice());
            s.setQuantity(i.getQuantity());
            return s;
        }).toList());
        // 合并售后：订单详情直接带出售后单(状态/回寄/审核备注)，前端不再单独查
        vo.setRefund(refundService.latestByOrderNo(memberId, orderNo));
        return vo;
    }

    // ==================== 取消/关单/支付/确认 ====================

    @Override
    @Transactional
    public void cancel(Long memberId, String orderNo) {
        requireOwned(memberId, orderNo);
        // CAS 抢占"取消"：先判断再写(updateById 整实体)会有两个坑——
        //   ① 与支付并发时可能把已支付订单改成已取消并放回库存（钱收了货没发）
        //   ② 与超时关单并发时两次回补库存（凭空多卖）
        // 因此状态迁移一律用条件 UPDATE，只有"抢到"的那一方才回补库存。
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getOrderStatus, OrderStatus.CANCELED)
                .set(Order::getCancelTime, LocalDateTime.now())
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getOrderStatus, OrderStatus.WAIT_PAY)
                .eq(Order::getPayStatus, OrderPayStatus.UNPAID));
        if (updated == 0) {
            throw new BusinessException(409, "当前状态不可取消");
        }
        releaseStock(orderNo, StockChangeType.CANCEL_RESTORE);
        // P5 步骤 E：取消必须把券还回去（旧实现只回补库存、券被永久烧掉——存量缺陷）
        releaseCouponOnClose(requireOrder(orderNo), OrderClosedMessage.REASON_CANCEL);
    }

    @Override
    @Transactional
    public void payMock(Long memberId, PayMockRequest request) {
        requestValidator.check(request);
        Order order = requireOwned(memberId, request.getOrderNo());
        if (Boolean.FALSE.equals(request.getSuccess())) {
            return;   // 模拟失败：订单保持待支付，可重试/取消
        }
        // CAS 抢占"支付"：只有仍处于"待支付 + 未支付"的订单能被改成已支付。
        // 这样与取消/超时关单天然互斥（它们同样带 status=0/pay_status=0 条件），
        // 重复提交也不会走到写支付流水那一步。
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getOrderStatus, OrderStatus.WAIT_SHIP)
                .set(Order::getPayStatus, OrderPayStatus.PAID)
                .set(Order::getPayTime, LocalDateTime.now())
                .eq(Order::getOrderNo, order.getOrderNo())
                .eq(Order::getOrderStatus, OrderStatus.WAIT_PAY)
                .eq(Order::getPayStatus, OrderPayStatus.UNPAID));
        if (updated == 0) {
            Order latest = requireOrder(order.getOrderNo());
            if (latest.getPayStatus() != null && latest.getPayStatus() == OrderPayStatus.PAID) {
                return;   // 幂等：已支付（双击/重试）直接返回
            }
            throw new BusinessException(409, "订单状态不允许支付");
        }

        Payment payment = new Payment();
        payment.setPayNo(OrderNoGenerator.next());
        payment.setOrderNo(order.getOrderNo());
        payment.setMemberId(memberId);
        payment.setAmount(order.getPayAmount());
        payment.setChannel("MOCK");
        payment.setPayStatus(PaymentStatus.SUCCESS);
        payment.setPayTime(LocalDateTime.now());
        try {
            paymentMapper.insert(payment);
        } catch (DuplicateKeyException e) {
            // oms_payment 有 uk(order_no)：这里是"同一订单已有支付流水"的兜底，
            // 不该把已经成功的支付报成 500（前端会以为没付成功而重复支付）
            log.warn("支付流水已存在，按幂等处理: orderNo={}", order.getOrderNo());
        }

        // 支付成功 → 领域事件(事务提交后投递)：站内消息 + 当日支付统计
        orderEventPublisher.publishAfterCommit(
                OrderEventMessage.paid(order.getOrderNo(), memberId, order.getPayAmount()));

        // 支付成功 → 券核销（P5 步骤 E：三态的最后一态 3 → 1）
        //
        // 为什么核销放在这里而不是下单时（改造前就是下单即核销）：
        // 下单即核销的话，"下单成功但一直没支付、最后取消/超时"这条路径上的券**永远回不来**
        // ——库存会回补、券不会，这是 P5 修掉的存量缺陷（方案 §4.3.1 ①）。
        //
        // 为什么**不能**让失败影响支付：钱已经收了。这里若抛异常，用户看到"支付失败"会重复支付，
        // 而订单其实已支付——比"券没核销成功"严重得多。因此只记日志 + 交给每日对账/人工核对
        // （方案 §4.3.1 ③）。`couponCommandService.use` 的远程实现本身也是"绝不抛异常"的。
        if (order.getCouponId() != null) {
            try {
                boolean used = couponCommandService.use(memberId, order.getCouponId(), order.getOrderNo());
                if (!used) {
                    log.warn("支付成功但券核销未生效(需对账核对): orderNo={} couponMemberId={}",
                            order.getOrderNo(), order.getCouponId());
                }
            } catch (Exception e) {
                log.error("支付成功但券核销调用异常(需对账核对): orderNo={} couponMemberId={}",
                        order.getOrderNo(), order.getCouponId(), e);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Integer payResult(Long memberId, String orderNo) {
        return requireOwned(memberId, orderNo).getPayStatus();
    }

    @Override
    @Transactional
    public void confirm(Long memberId, String orderNo) {
        requireOwned(memberId, orderNo);
        // CAS 抢占"确认收货"：销量是无条件累加(sales = sales + n)，
        // 若两个请求都通过状态判断就会把销量累加两次（前台按销量排序直接被污染）
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getOrderStatus, OrderStatus.FINISHED)
                .set(Order::getFinishTime, LocalDateTime.now())
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getOrderStatus, OrderStatus.WAIT_RECEIVE));
        if (updated == 0) {
            throw new BusinessException(409, "仅待收货订单可确认收货");
        }
        // 累加销量(展示口径)：交给商品域（按 skuId 排序遍历 + SKU/SPU 同时累加 + 标记索引待同步）
        stockCommandService.incrementSales(orderNo, orderItemMapper
                .selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, orderNo))
                .stream()
                .map(item -> new StockLineVO(item.getSkuId(), item.getSpuId(), item.getQuantity()))
                .toList());
        // P4：发布 order.finished —— 评价域据此建"待评价"读模型，把"能不能评价"的校验
        // 全部变成它自己库内的判断，不必再同步调用订单域（这正是抽服务要消除的耦合）。
        // 明细快照取自 oms_order_item 自身已有列（spuTitle/skuImage），无需任何跨域查询。
        List<OrderItem> finishedItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, orderNo));
        orderEventPublisher.publishRawAfterCommit(MqTopology.EVENT_ROUTING_FINISHED, orderNo,
                new OrderFinishedMessage(orderNo, memberId, System.currentTimeMillis(),
                        finishedItems.stream()
                                .map(item -> new OrderFinishedMessage.Item(item.getId(), item.getSpuId(),
                                        item.getSkuId(), item.getSpuTitle(), item.getSkuImage(),
                                        item.getQuantity() == null ? 0 : item.getQuantity()))
                                .toList()));
    }

    @Override
    public void deleteOrder(Long memberId, String orderNo) {
        Order order = requireOwned(memberId, orderNo);
        if (order.getOrderStatus() != OrderStatus.FINISHED && order.getOrderStatus() != OrderStatus.CANCELED) {
            throw new BusinessException(409, "仅已完成或已取消的订单可删除");
        }
        orderMapper.deleteById(order.getId());   // 逻辑删除
    }

    @Override
    @Transactional
    public void closeExpiredOrders() {
        List<Order> expired = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderStatus, OrderStatus.WAIT_PAY)
                .lt(Order::getPayExpireTime, LocalDateTime.now()));
        for (Order order : expired) {
            closeExpiredOrder(order);
        }
    }

    @Override
    @Transactional
    public boolean closeIfExpired(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            return false;
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        return order != null && closeExpiredOrder(order);
    }

    /**
     * 关单公共逻辑（MQ 消费者与兜底扫描任务共用）。
     *
     * <p>幂等：只有"状态=待支付"且"已过支付截止时间"才动手。因此消息重复投递、
     * 支付成功后延迟消息才到期、兜底扫描与消费者同时命中，都不会重复回补库存。
     *
     * @return 本次是否真的关掉了订单
     */
    private boolean closeExpiredOrder(Order order) {
        if (order.getOrderStatus() == null || order.getOrderStatus() != OrderStatus.WAIT_PAY) {
            return false;
        }
        if (order.getPayExpireTime() != null && order.getPayExpireTime().isAfter(LocalDateTime.now())) {
            return false;   // 未到期(消息提前到达/时钟偏差) → 交给兜底扫描
        }
        // CAS 抢占关单：兜底扫描任务与 MQ 消费者可能同时对同一单动手，
        // 只有抢到的那一方回补库存，否则库存会被回补两次（凭空多卖）
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getOrderStatus, OrderStatus.CANCELED)
                .set(Order::getCancelTime, LocalDateTime.now())
                .eq(Order::getOrderNo, order.getOrderNo())
                .eq(Order::getOrderStatus, OrderStatus.WAIT_PAY)
                .eq(Order::getPayStatus, OrderPayStatus.UNPAID));
        if (updated == 0) {
            return false;   // 已被支付/已被别人关掉
        }
        releaseStock(order.getOrderNo(), StockChangeType.TIMEOUT_RESTORE);
        // P5 步骤 E：超时关单同样要把券还回去（旧实现里这条路径上的券被烧掉）
        releaseCouponOnClose(order, OrderClosedMessage.REASON_TIMEOUT);
        log.info("订单超时关单: orderNo={} 回补库存并置为已取消", order.getOrderNo());
        return true;
    }

    // ==================== 后台操作 ====================

    @Override
    @Transactional
    public void shipByAdmin(String orderNo, ShipRequest request) {
        Order order = requireOrder(orderNo);
        // 先按原口径判状态(404/409 排在 400 之前)：状态不对时不该因为"物流没填"而改变提示
        if (order.getOrderStatus() == null || order.getOrderStatus() != OrderStatus.WAIT_SHIP) {
            throw new BusinessException(409, "仅待发货订单可发货");
        }
        requestValidator.check(request);
        // CAS：与后台"关闭订单"互斥（否则可能发出一个已被关闭/已退款的订单）
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getOrderStatus, OrderStatus.WAIT_RECEIVE)
                .set(Order::getLogisticsCompany, request.getLogisticsCompany())
                .set(Order::getLogisticsNo, request.getLogisticsNo())
                .set(Order::getShipTime, LocalDateTime.now())
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getOrderStatus, OrderStatus.WAIT_SHIP));
        if (updated == 0) {
            throw new BusinessException(409, "仅待发货订单可发货");
        }

        // 已发货 → 领域事件：给买家发站内消息
        orderEventPublisher.publishAfterCommit(OrderEventMessage.shipped(
                order.getOrderNo(), order.getMemberId(),
                request.getLogisticsCompany() + " " + request.getLogisticsNo()));
    }

    @Override
    @Transactional
    public void closeByAdmin(String orderNo, CloseOrderRequest request) {
        Order order = requireOrder(orderNo);
        int status = order.getOrderStatus() == null ? -1 : order.getOrderStatus();
        // 原来"状态不对"是在分支末尾统一抛 409；这里提到前面，好让参数校验(400)排在状态校验(409)之后
        if (status != OrderStatus.WAIT_PAY && status != OrderStatus.WAIT_SHIP) {
            throw new BusinessException(409, "当前状态不可关闭");
        }
        requestValidator.check(request);   // 关闭原因必填(约束写在 CloseOrderRequest 上，原因只进日志)
        String reason = request.getReason();
        if (status == OrderStatus.WAIT_PAY) {
            // 未支付：CAS 关单 → 已关闭(5)，只有抢到的一方回补库存
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                    .set(Order::getOrderStatus, OrderStatus.CLOSED)
                    .set(Order::getCloseTime, LocalDateTime.now())
                    .eq(Order::getOrderNo, orderNo)
                    .eq(Order::getOrderStatus, OrderStatus.WAIT_PAY)
                    .eq(Order::getPayStatus, OrderPayStatus.UNPAID));
            if (updated == 0) {
                throw new BusinessException(409, "当前状态不可关闭");
            }
            releaseStock(orderNo, StockChangeType.CANCEL_RESTORE);
            // P5 步骤 E：后台关单（未支付分支）也要把券还回去
            //   这一支的券还停在 LOCKED(3)（没支付过、没核销），unlock 的 3→0 正好把它放回 UNUSED。
            releaseCouponOnClose(order, OrderClosedMessage.REASON_ADMIN_CLOSE);
            log.info("后台关闭订单: orderNo={} 原因={}", orderNo, reason);
        } else {
            // 已支付未发货：模拟全额退款 + 回补库存 → 已退款(7)
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                    .set(Order::getOrderStatus, OrderStatus.REFUNDED)
                    .set(Order::getPayStatus, OrderPayStatus.REFUNDED)
                    .set(Order::getCloseTime, LocalDateTime.now())
                    .eq(Order::getOrderNo, orderNo)
                    .eq(Order::getOrderStatus, OrderStatus.WAIT_SHIP)
                    .eq(Order::getPayStatus, OrderPayStatus.PAID));
            if (updated == 0) {
                throw new BusinessException(409, "当前状态不可关闭");
            }
            releaseStock(orderNo, StockChangeType.CANCEL_RESTORE);
            // P5 步骤 E：后台关单（已支付→退款分支）同样走券侧收尾。
            //   ⚠️ 这一支的券已经是 USED(1)（支付时核销过），而 unlock 的 CAS 是 `3 → 0`，
            //   对已核销的券影响 0 行 ⇒ **不会误放**；"退款不返券"是既有行为，本次不改（方案 §4.3.1）。
            //   发 order.closed 也一样安全：marketing 那边 unpack 出来的也是幂等的 unlock。
            releaseCouponOnClose(order, OrderClosedMessage.REASON_ADMIN_CLOSE);
            paymentMapper.update(null, new LambdaUpdateWrapper<Payment>()
                    .setSql("pay_status = " + PaymentStatus.REFUNDED)
                    .eq(Payment::getOrderNo, orderNo));
            // 与售后链路对齐：后台关闭产生的退款也要发领域事件(站内消息 + 当日退款统计)
            orderEventPublisher.publishAfterCommit(
                    OrderEventMessage.refundSettled(orderNo, order.getMemberId(), order.getPayAmount()));
            log.info("后台关闭并退款: orderNo={} 原因={}", orderNo, reason);
        }
    }

    private Order requireOrder(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    // ==================== 私有 ====================

    private record PurchaseLine(SkuSnapshotVO sku, SpuSnapshotVO spu, int quantity) {
        long price() {
            return sku.getPrice();
        }
    }

    private List<PurchaseLine> resolveLines(Long memberId, OrderCreateRequest request, boolean forCreate) {
        boolean cartSource = "CART".equalsIgnoreCase(request.getSource());
        boolean buyNowSource = "BUY_NOW".equalsIgnoreCase(request.getSource());
        if (!cartSource && !buyNowSource) {
            throw new BusinessException(400, "下单来源不合法");
        }
        List<CartItemSnapshotVO> cartRows = new ArrayList<>();
        if (cartSource) {
            if (request.getCartItemIds() == null || request.getCartItemIds().isEmpty()) {
                throw new BusinessException(400, "请选择要结算的商品");
            }
            cartRows = cartCheckoutService.items(memberId, request.getCartItemIds());
            if (cartRows.isEmpty() || cartRows.size() != request.getCartItemIds().size()) {
                throw new BusinessException(404, "购物车商品不存在");
            }
        } else {
            if (request.getBuyNow() == null || request.getBuyNow().getSkuId() == null) {
                throw new BusinessException(400, "缺少立即购买的商品");
            }
        }

        List<PurchaseLine> lines = new ArrayList<>();
        if (cartSource) {
            for (CartItemSnapshotVO row : cartRows) {
                lines.add(buildLine(row.getSkuId(), row.getQuantity(), forCreate));
            }
        } else {
            int qty = request.getBuyNow().getQuantity() == null ? 1 : request.getBuyNow().getQuantity();
            lines.add(buildLine(request.getBuyNow().getSkuId(), qty, forCreate));
        }
        return lines;
    }

    private PurchaseLine buildLine(Long skuId, int quantity, boolean strict) {
        if (quantity < 1) {
            throw new BusinessException(400, "购买数量不合法");
        }
        // 走商品域的只读契约取快照（不再直连 pms_sku/pms_spu）
        SkuSnapshotVO sku = productQueryService.sku(skuId);
        if (sku == null || !Integer.valueOf(EnableStatus.ENABLED).equals(sku.getStatus())) {
            throw new BusinessException(409, "商品已下架或规格失效");
        }
        SpuSnapshotVO spu = sku.getSpuId() == null ? null : productQueryService.spu(sku.getSpuId());
        if (spu == null || !Integer.valueOf(EnableStatus.ENABLED).equals(spu.getStatus())) {
            throw new BusinessException(409, "商品已下架");
        }
        if (strict) {
            int stock = sku.getStock() == null ? 0 : sku.getStock();
            if (stock < quantity) {
                throw new BusinessException(409, "库存不足：" + spu.getTitle());
            }
        }
        return new PurchaseLine(sku, spu, quantity);
    }

    /**
     * 回补订单内所有商品的库存(取消/超时/后台关闭)：写表与流水都在商品域内完成。
     *
     * <p>⚠️ <b>P6-4（D1）：回补统一走 {@link com.mall.trade.oms.support.StockReleaseAfterCommit}</b>
     * ——它把远程 {@code release} 放到**事务提交之后**执行。原来是在事务内直接调用，而 release 现在是
     * 跨进程的：本地回滚覆盖不到它 ⇒ "库存已加回、本地状态却回滚了" ⇒ 重试会造成**重复回补（库存凭空多）**。
     * 本方法有 **4 个**调用点（取消 552/780/797、超时关单 722），所以**只在这里改一处**，
     * 保证"一条规则、一个机制"，不会出现某条路径语义不同。
     */
    private void releaseStock(String orderNo, int changeType) {
        List<StockLineVO> lines = orderItemMapper
                .selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, orderNo))
                .stream()
                .map(item -> new StockLineVO(item.getSkuId(), item.getSpuId(), item.getQuantity()))
                .toList();
        stockReleaseAfterCommit.release(orderNo, lines, changeType);
    }

    private Order requireOwned(Long memberId, String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null || !order.getMemberId().equals(memberId)) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    /** 取收货地址：默认地址的选择规则、以及"是不是你的地址"的归属校验都在会员域内完成 */
    private AddressSnapshotVO requireAddress(Long memberId, Long addressId) {
        AddressSnapshotVO address = addressId == null
                ? addressQueryService.defaultAddress(memberId)
                : addressQueryService.address(memberId, addressId);
        if (address == null) {
            throw new BusinessException(400, "请选择有效的收货地址");
        }
        return address;
    }

    private AddressInfoVO toAddressInfo(AddressSnapshotVO a) {
        AddressInfoVO vo = new AddressInfoVO();
        vo.setId(a.getId());
        vo.setReceiverName(a.getReceiverName());
        vo.setReceiverPhone(a.getReceiverPhone());
        vo.setFullAddress(fullAddress(a));
        return vo;
    }

    private static String fullAddress(AddressSnapshotVO a) {
        return concat(a.getProvinceName()) + concat(a.getCityName()) + concat(a.getDistrictName())
                + (a.getDetail() == null ? "" : a.getDetail());
    }

    private static String concat(String s) {
        return s == null ? "" : s;
    }


}