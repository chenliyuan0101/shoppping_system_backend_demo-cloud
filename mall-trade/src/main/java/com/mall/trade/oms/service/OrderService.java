package com.mall.trade.oms.service;

import com.mall.trade.common.PageResult;
import com.mall.trade.oms.dto.CloseOrderRequest;
import com.mall.trade.oms.dto.OrderCreateRequest;
import com.mall.trade.oms.dto.OrderDetailVO;
import com.mall.trade.oms.dto.OrderListVO;
import com.mall.trade.oms.dto.PayMockRequest;
import com.mall.trade.oms.dto.PreviewResult;
import com.mall.trade.oms.dto.ShipRequest;
import com.mall.common.support.MemberId;

/**
 * 订单服务(见《接口文档.md》2.6/2.7)。下单采用"单列库存"：下单即扣可售库存，取消/超时/退款回补。
 */
public interface OrderService {

    /** 结算页预览(只读核算，不落单) */
    PreviewResult preview(Long memberId, OrderCreateRequest request);

    /** 创建订单：二次核算 → 预占库存 → 快照落单，返回订单号 */
    String createOrder(Long memberId, OrderCreateRequest request);

    /**
     * 我的订单分页。
     *
     * @param status    订单状态 0-7(null 全部)
     * @param afterSale true 时只看「退款/售后」：收录所有存在售后单的订单(处理中/已退款/被拒/已撤销)
     */
    PageResult<OrderListVO> page(Long memberId, Integer status, Boolean afterSale, long pageNum, long pageSize);

    OrderDetailVO detail(Long memberId, String orderNo);

    /** 取消订单(仅待支付，回补库存) */
    void cancel(Long memberId, String orderNo);

    /** 模拟支付 */
    void payMock(Long memberId, PayMockRequest request);

    /** 支付状态查询(订单支付状态) */
    Integer payResult(Long memberId, String orderNo);

    /** 确认收货(仅待收货，累加销量) */
    void confirm(Long memberId, String orderNo);

    /** 删除已完成/已取消订单(逻辑删除) */
    void deleteOrder(Long memberId, String orderNo);

    /** 超时关单任务：支付超时订单自动取消并回补库存(兜底扫描，与 MQ 消费者共用同一段逻辑) */
    void closeExpiredOrders();

    /**
     * 到期关单（单笔）：MQ 延迟消息到期后调用，也用于兜底扫描。
     *
     * <p>幂等：仅当订单仍是「待支付」且已过支付截止时间才关单并回补库存；
     * 其余情况（已支付/已取消/未到期/订单不存在）直接返回 false，不产生副作用。
     *
     * @return 本次是否真的关掉了订单
     */
    boolean closeIfExpired(String orderNo);

    // ---------- 后台操作(管理员，鉴权在 Controller) ----------

    /** 后台发货：待发货(1)→待收货(2)，填物流信息(必填约束见 {@code ShipRequest}) */
    void shipByAdmin(String orderNo, ShipRequest request);

    /** 后台关闭订单：待支付回补库存→已关闭(5)；已支付额外标记全额退款并回补库存→已退款(7) */
    void closeByAdmin(String orderNo, CloseOrderRequest request);
}
