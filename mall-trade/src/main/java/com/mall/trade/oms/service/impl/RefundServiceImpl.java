package com.mall.trade.oms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mall.trade.common.BusinessException;
import com.mall.trade.common.RequestValidator;
import com.mall.common.support.PageKit;
import com.mall.common.support.JsonKit;
import com.mall.trade.common.PageResult;
import com.mall.trade.oms.domain.OmsRefund;
import com.mall.trade.oms.domain.Order;
import com.mall.trade.oms.domain.OrderItem;
import com.mall.trade.oms.domain.Payment;
import com.mall.trade.oms.dto.RefundApplyRequest;
import com.mall.trade.oms.dto.RefundVO;
import com.mall.trade.oms.dto.RejectRequest;
import com.mall.trade.oms.dto.ReturnLogisticsRequest;
import com.mall.trade.oms.mapper.OrderItemMapper;
import com.mall.trade.oms.mapper.OrderMapper;
import com.mall.trade.oms.mapper.PaymentMapper;
import com.mall.trade.oms.mapper.RefundMapper;
import com.mall.trade.oms.mq.OrderEventMessage;
import com.mall.trade.oms.mq.OrderEventPublisher;
import com.mall.trade.oms.service.RefundService;
import com.mall.trade.common.constant.OrderPayStatus;
import com.mall.trade.common.constant.OrderStatus;
import com.mall.trade.oms.support.OrderTexts;
import com.mall.trade.oms.support.PaymentStatus;
import com.mall.trade.common.constant.RefundStatus;
import com.mall.trade.oms.support.RefundTexts;
import com.mall.trade.common.constant.StockChangeType;
import com.mall.trade.common.dto.StockLineVO;
import com.mall.trade.pms.service.StockCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import com.mall.trade.oms.support.OrderNoGenerator;
import com.mall.common.support.MemberId;

/**
 * 退款售后实现。
 * 一期全额退款且模拟执行，售后与订单状态联动(订单状态码见 OrderStatus，支付状态见 OrderPayStatus/PaymentStatus)：
 *   - 仅退款(未发货)：后台同意 → 售后完成，订单 → 7 已退款
 *   - 退货退款(已收货)：后台同意 → 订单 → 6 退款中(等待回寄) → 卖家确认收货 → 订单 → 7 已退款
 *   - 拒绝/用户撤销：仅待处理阶段可操作，订单状态不变
 *   - 完成时：oms_order.pay_status→2(全额退款)、oms_payment.pay_status→2、库存回补(change_type=4 流水)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private final RefundMapper refundMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentMapper paymentMapper;
    /** 商品域的库存写契约（P0 最后一批：退款回补不再由交易域直接改 pms_sku） */
    private final StockCommandService stockCommandService;
    /**
     * P6-4（D1）：退款回补统一在**事务提交后**执行。
     *
     * <p>为什么退款这条尤其必须如此：{@code settleRefund} 的防重闸门是本地 CAS
     * （{@code pay_status != REFUNDED → REFUNDED}）。release 现在是跨进程的、本地回滚覆盖不到，
     * 若留在事务内 ⇒ "库存已加回、CAS 却回滚了" ⇒ 重试审核再回补一次 ⇒ **库存凭空多**（超卖方向、不可逆）。
     * 见 {@link com.mall.trade.oms.support.StockReleaseAfterCommit} 的类注释。
     */
    private final com.mall.trade.oms.support.StockReleaseAfterCommit stockReleaseAfterCommit;
    /** 退款到账领域事件(站内消息 + 当日退款统计) */
    private final OrderEventPublisher orderEventPublisher;
    /** 请求参数校验(约束注解写在 DTO 字段上，此处统一触发) */
    private final RequestValidator requestValidator;

    // ==================== 用户侧 ====================

    @Override
    @Transactional
    public String apply(Long memberId, RefundApplyRequest request) {
        if (request.getRefundType() == null
                || (request.getRefundType() != RefundTexts.TYPE_ONLY_MONEY && request.getRefundType() != RefundTexts.TYPE_RETURN_GOODS)) {
            throw new BusinessException(400, "售后类型不合法");
        }
        requestValidator.check(request);
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, request.getOrderNo()));
        if (order == null || !order.getMemberId().equals(memberId)) {
            throw new BusinessException(404, "订单不存在");
        }
        if (order.getPayStatus() == null || order.getPayStatus() != OrderPayStatus.PAID) {
            throw new BusinessException(409, "订单未支付，无法申请售后(请直接取消订单)");
        }
        // "该订单已有处理中的售后单"必须用**锁定读**判断：
        // REPEATABLE READ 下普通 COUNT 是快照读，申请页双击会让两个请求都读到 0 然后各插一条
        // （同一订单两张待处理售后单 → 后续并发审核 → 重复退款/重复回补）。
        // 这里加 FOR UPDATE：对 order_no 索引区间加锁，把同一订单的并发申请串行化。
        //
        // 说明：更彻底的做法是 DB 层唯一键（生成列 open_flag + uk_order_open），
        // 但那要求先清理历史数据里已存在的"同订单两张未结案售后单"（演示数据里确实有一条），
        // 见《代码审计与加固记录.md》§2.6 —— 此处先用锁定读把并发窗口关掉。
        boolean orderOpenRefund = refundMapper.selectCount(new LambdaQueryWrapper<OmsRefund>()
                .eq(OmsRefund::getOrderNo, request.getOrderNo())
                .in(OmsRefund::getStatus, RefundStatus.PENDING, RefundStatus.AGREED)
                .last("FOR UPDATE")) > 0;
        if (orderOpenRefund) {
            throw new BusinessException(409, "该订单已有处理中的售后单");
        }
        Integer os = order.getOrderStatus();
        if (request.getRefundType() == RefundTexts.TYPE_ONLY_MONEY && os != OrderStatus.WAIT_SHIP) {
            throw new BusinessException(400, "仅待发货订单支持仅退款");
        }
        if (request.getRefundType() == RefundTexts.TYPE_RETURN_GOODS && os != OrderStatus.WAIT_RECEIVE && os != OrderStatus.FINISHED) {
            throw new BusinessException(400, "仅待收货/已完成订单支持退货退款");
        }

        OmsRefund refund = new OmsRefund();
        refund.setRefundNo(OrderNoGenerator.next());
        refund.setOrderNo(order.getOrderNo());
        refund.setMemberId(memberId);
        refund.setRefundType(request.getRefundType());
        refund.setReason(request.getReason());
        refund.setDescription(request.getDescription());
        refund.setImages(request.getImages() == null || request.getImages().isEmpty()
                ? null : JsonKit.toJson(request.getImages()));
        refund.setRefundAmount(order.getPayAmount());
        refund.setStatus(RefundStatus.PENDING);
        refundMapper.insert(refund);
        return refund.getRefundNo();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RefundVO> pageUser(Long memberId, Integer status, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<OmsRefund> base = new LambdaQueryWrapper<OmsRefund>()
                .eq(OmsRefund::getMemberId, memberId)
                .eq(status != null, OmsRefund::getStatus, status)
                .orderByDesc(OmsRefund::getCreateTime);
        long total = refundMapper.selectCount(base);
        List<OmsRefund> list = refundMapper.selectList(base.last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, list.stream().map(this::toVO).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public RefundVO detailUser(Long memberId, String refundNo) {
        OmsRefund refund = requireOwned(memberId, refundNo);
        return toVO(refund);
    }

    @Override
    @Transactional(readOnly = true)
    public RefundVO latestByOrderNo(Long memberId, String orderNo) {
        OmsRefund refund = refundMapper.selectOne(new LambdaQueryWrapper<OmsRefund>()
                .eq(OmsRefund::getMemberId, memberId)
                .eq(OmsRefund::getOrderNo, orderNo)
                .orderByDesc(OmsRefund::getId)
                .last("LIMIT 1"));
        return refund == null ? null : toVO(refund);
    }

    @Override
    @Transactional
    public void cancelUser(Long memberId, String refundNo) {
        OmsRefund refund = requireOwned(memberId, refundNo);
        if (refund.getStatus() != RefundStatus.PENDING) {
            throw new BusinessException(409, "当前状态不可取消");
        }
        refund.setStatus(RefundStatus.CANCELED);
        refundMapper.updateById(refund);
    }

    @Override
    @Transactional
    public void submitReturnLogistics(Long memberId, String refundNo, ReturnLogisticsRequest request) {
        OmsRefund refund = requireOwned(memberId, refundNo);
        if (refund.getRefundType() != RefundTexts.TYPE_RETURN_GOODS || refund.getStatus() != RefundStatus.AGREED) {
            throw new BusinessException(409, "仅退货退款且审核同意后可填写回寄信息");
        }
        requestValidator.check(request);
        refund.setReturnCompany(request.getReturnCompany());
        refund.setReturnTrackingNo(request.getReturnTrackingNo());
        refundMapper.updateById(refund);
    }

    // ==================== 后台 ====================

    @Override
    @Transactional(readOnly = true)
    public PageResult<RefundVO> pageAdmin(Integer status, Integer refundType, String refundNo,
                                          long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<OmsRefund> base = new LambdaQueryWrapper<OmsRefund>()
                .eq(status != null, OmsRefund::getStatus, status)
                .eq(refundType != null, OmsRefund::getRefundType, refundType)
                .like(StringUtils.hasText(refundNo), OmsRefund::getRefundNo, refundNo)
                .orderByAsc(OmsRefund::getStatus).orderByDesc(OmsRefund::getCreateTime);
        long total = refundMapper.selectCount(base);
        List<OmsRefund> list = refundMapper.selectList(base.last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, list.stream().map(this::toVO).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public RefundVO detailAdmin(Long id) {
        OmsRefund refund = refundMapper.selectById(id);
        if (refund == null) {
            throw new BusinessException(404, "售后单不存在");
        }
        return toVO(refund);
    }

    @Override
    @Transactional
    public void approve(Long id, Long operatorId) {
        OmsRefund refund = require(id);
        if (refund.getStatus() != RefundStatus.PENDING) {
            throw new BusinessException(409, "仅待处理的售后单可审核");
        }
        refund.setAuditBy(operatorId);
        refund.setAuditTime(LocalDateTime.now());
        if (refund.getRefundType() == RefundTexts.TYPE_ONLY_MONEY) {
            // 仅退款(未发货)：同意即执行模拟退款 → 订单已退款(7)
            refund.setStatus(RefundStatus.FINISHED);
            refund.setFinishTime(LocalDateTime.now());
            refundMapper.updateById(refund);
            settleRefund(refund.getOrderNo());
        } else {
            // 退货退款：等待买家回寄 → 订单进入「退款中(6)」
            refund.setStatus(RefundStatus.AGREED);
            refundMapper.updateById(refund);
            markOrderStatus(refund.getOrderNo(), OrderStatus.REFUNDING);
        }
    }

    @Override
    @Transactional
    public void reject(Long id, RejectRequest request, Long operatorId) {
        OmsRefund refund = require(id);
        if (refund.getStatus() != RefundStatus.PENDING) {
            throw new BusinessException(409, "仅待处理的售后单可拒绝");
        }
        requestValidator.check(request);   // 拒绝原因必填(约束写在 RejectRequest 上)
        refund.setStatus(RefundStatus.REJECTED);
        refund.setAuditBy(operatorId);
        refund.setAuditTime(LocalDateTime.now());
        refund.setAuditRemark(request.getReason());
        refundMapper.updateById(refund);
    }

    @Override
    @Transactional
    public void received(Long id, Long operatorId) {
        OmsRefund refund = require(id);
        if (refund.getRefundType() != RefundTexts.TYPE_RETURN_GOODS || refund.getStatus() != RefundStatus.AGREED) {
            throw new BusinessException(409, "仅退货退款且已同意的售后单可确认收货");
        }
        refund.setReceivedTime(LocalDateTime.now());
        refund.setStatus(RefundStatus.FINISHED);
        refund.setFinishTime(LocalDateTime.now());
        refundMapper.updateById(refund);
        settleRefund(refund.getOrderNo());
    }

    // ==================== 私有 ====================

    /** 模拟退款落账：订单标记已退款(7) + 全额退款 + 库存回补(未发货/退货到货场景) */
    private void settleRefund(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null) {
            return;   // 订单不存在，幂等退出
        }
        // CAS 抢占"退款只执行一次"：仅当订单尚未退款(pay_status != 2)才能改成功。
        // 若只做"读出来判断 payStatus==2"，同一订单的两张售后单被并发审核时，
        // 双方都会通过判断 → 库存被回补两次（凭空多卖）、退款事件重复投递。
        int claimed = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getPayStatus, OrderPayStatus.REFUNDED)
                .set(Order::getOrderStatus, OrderStatus.REFUNDED)
                .eq(Order::getOrderNo, orderNo)
                .ne(Order::getPayStatus, OrderPayStatus.REFUNDED));
        if (claimed == 0) {
            log.info("订单已退款，跳过重复结算: orderNo={}", orderNo);
            return;
        }

        // 回补库存(一次)：写表、流水、索引标记都在商品域内完成。
        // ⚠️ P6-4（D1）：**经共享组件在事务提交后执行**（不是直连）—— 跨进程的 release 不能被本地回滚撤销，
        //    留在事务内会造成"重复回补（库存凭空多）"，见 StockReleaseAfterCommit 的类注释。
        List<StockLineVO> releaseLines = orderItemMapper
                .selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, orderNo))
                .stream()
                .map(item -> new StockLineVO(item.getSkuId(), item.getSpuId(), item.getQuantity()))
                .toList();
        stockReleaseAfterCommit.release(orderNo, releaseLines, StockChangeType.REFUND_RESTORE);
        paymentMapper.update(null, new LambdaUpdateWrapper<Payment>()
                .setSql("pay_status = " + PaymentStatus.REFUNDED)
                .eq(Payment::getOrderNo, orderNo));

        // 退款到账 → 领域事件(事务提交后投递)：站内消息 + 当日退款统计
        orderEventPublisher.publishAfterCommit(
                OrderEventMessage.refundSettled(orderNo, order.getMemberId(), order.getPayAmount()));
    }

    /** 仅更新订单状态(售后流转用，如退货退款同意后置「退款中」) */
    private void markOrderStatus(String orderNo, int status) {
        orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getOrderStatus, status)
                .eq(Order::getOrderNo, orderNo));
    }

    private OmsRefund requireOwned(Long memberId, String refundNo) {
        OmsRefund refund = refundMapper.selectOne(new LambdaQueryWrapper<OmsRefund>()
                .eq(OmsRefund::getRefundNo, refundNo));
        if (refund == null || !refund.getMemberId().equals(memberId)) {
            throw new BusinessException(404, "售后单不存在");
        }
        return refund;
    }

    private OmsRefund require(Long id) {
        OmsRefund refund = refundMapper.selectById(id);
        if (refund == null) {
            throw new BusinessException(404, "售后单不存在");
        }
        return refund;
    }

    private RefundVO toVO(OmsRefund r) {
        RefundVO vo = new RefundVO();
        vo.setId(r.getId());
        vo.setRefundNo(r.getRefundNo());
        vo.setOrderNo(r.getOrderNo());
        vo.setRefundType(r.getRefundType());
        vo.setTypeText(RefundTexts.type(r.getRefundType()));
        vo.setReason(r.getReason());
        vo.setDescription(r.getDescription());
        vo.setImages(JsonKit.toImageList(r.getImages()));
        vo.setRefundAmount(r.getRefundAmount());
        vo.setStatus(r.getStatus());
        vo.setStatusText(RefundTexts.status(r.getStatus()));
        vo.setReturnCompany(r.getReturnCompany());
        vo.setReturnTrackingNo(r.getReturnTrackingNo());
        vo.setAuditRemark(r.getAuditRemark());
        vo.setCreateTime(r.getCreateTime());
        vo.setFinishTime(r.getFinishTime());
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, r.getOrderNo()));
        if (order != null) {
            vo.setOrderStatusText(OrderTexts.orderStatus(order.getOrderStatus()));
        }
        return vo;
    }

}
