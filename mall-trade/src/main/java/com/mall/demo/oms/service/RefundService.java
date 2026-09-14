package com.mall.demo.oms.service;

import com.mall.demo.common.PageResult;
import com.mall.demo.oms.dto.RefundApplyRequest;
import com.mall.demo.oms.dto.RefundVO;
import com.mall.demo.oms.dto.RejectRequest;
import com.mall.demo.oms.dto.ReturnLogisticsRequest;

/**
 * 退款售后服务(用户申请/查询/回寄；后台审核/受理)。
 */
public interface RefundService {

    /** 用户申请售后 → 返回售后单号 */
    String apply(Long memberId, RefundApplyRequest request);

    PageResult<RefundVO> pageUser(Long memberId, Integer status, long pageNum, long pageSize);

    RefundVO detailUser(Long memberId, String refundNo);

    /** 按订单号取最近一次售后单(订单详情合并展示售后进度用)，无则 null */
    RefundVO latestByOrderNo(Long memberId, String orderNo);

    /** 用户取消申请(仅待处理) */
    void cancelUser(Long memberId, String refundNo);

    /** 退货退款：填写回寄物流(仅退货退款/已同意) */
    void submitReturnLogistics(Long memberId, String refundNo, ReturnLogisticsRequest request);

    // ---------- 后台 ----------
    PageResult<RefundVO> pageAdmin(Integer status, Integer refundType, String refundNo,
                                   long pageNum, long pageSize);

    RefundVO detailAdmin(Long id);

    /** 同意：仅退款=直接模拟退款完成(订单→已退款7)；退货退款=进入待回寄(订单→退款中6) */
    void approve(Long id, Long operatorId);

    /** 拒绝(订单状态不变，售后单→已拒绝)，拒绝原因必填(约束见 {@code RejectRequest}) */
    void reject(Long id, RejectRequest request, Long operatorId);

    /** 卖家确认收到退货 → 完成退款(订单→已退款7) */
    void received(Long id, Long operatorId);
}
