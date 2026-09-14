package com.mall.demo.oms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.demo.common.contract.MemberQueryService;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.PageKit;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.dto.MemberBriefVO;
import com.mall.demo.oms.domain.Order;
import com.mall.demo.oms.domain.OrderItem;
import com.mall.demo.oms.dto.AdminOrderDetailVO;
import com.mall.demo.oms.dto.AdminOrderListVO;
import com.mall.demo.oms.dto.OrderDetailVO;
import com.mall.demo.oms.mapper.OrderItemMapper;
import com.mall.demo.oms.mapper.OrderMapper;
import com.mall.demo.oms.service.AdminOrderQueryService;
import com.mall.demo.oms.support.OrderTexts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后台订单查询实现。
 */
@Service
@RequiredArgsConstructor
public class AdminOrderQueryServiceImpl implements AdminOrderQueryService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    /** 会员域的查询契约（P0 边界冻结：后台订单要显示会员信息，但不再直连 ums_member） */
    private final MemberQueryService memberQueryService;

    @Override
    @Transactional(readOnly = true)
    public PageResult<AdminOrderListVO> page(String orderNo, String memberKeyword, Integer status, Integer payStatus,
                                             LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                             long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);

        List<Long> memberIds = null;
        if (StringUtils.hasText(memberKeyword)) {
            // "会员怎么被搜出来"的知识留在会员域：本域只拿 id 集合，在自己的查询里按 id 过滤
            memberIds = memberQueryService.searchIds(memberKeyword);
            if (memberIds.isEmpty()) {
                return PageResult.of(0, page, size, List.of());
            }
        }

        LambdaQueryWrapper<Order> base = new LambdaQueryWrapper<Order>()
                .like(StringUtils.hasText(orderNo), Order::getOrderNo, orderNo)
                .eq(status != null, Order::getOrderStatus, status)
                .eq(payStatus != null, Order::getPayStatus, payStatus)
                .ge(createTimeStart != null, Order::getCreateTime, createTimeStart)
                .lt(createTimeEnd != null, Order::getCreateTime, createTimeEnd)
                .in(memberIds != null, Order::getMemberId, memberIds);
        long total = orderMapper.selectCount(base);
        List<Order> orders = orderMapper.selectList(base.orderByDesc(Order::getCreateTime)
                .last("LIMIT " + ((page - 1) * size) + "," + size));

        Map<Long, MemberBriefVO> memberMap = memberQueryService
                .briefs(orders.stream().map(Order::getMemberId).toList())
                .stream().collect(Collectors.toMap(MemberBriefVO::getId, m -> m));

        List<AdminOrderListVO> vos = orders.stream().map(o -> {
            AdminOrderListVO vo = new AdminOrderListVO();
            vo.setOrderNo(o.getOrderNo());
            vo.setMemberId(o.getMemberId());
            MemberBriefVO m = memberMap.get(o.getMemberId());
            vo.setMemberName(m == null ? null : m.getNickname());
            vo.setMemberPhone(m == null ? null : m.getPhone());
            vo.setOrderStatus(o.getOrderStatus());
            vo.setPayStatus(o.getPayStatus());
            vo.setStatusText(OrderTexts.orderStatus(o.getOrderStatus()));
            vo.setPayAmount(o.getPayAmount());
            List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                    .eq(OrderItem::getOrderNo, o.getOrderNo()));
            vo.setItemCount(items.stream().mapToInt(OrderItem::getQuantity).sum());
            vo.setCreateTime(o.getCreateTime());
            vo.setPayTime(o.getPayTime());
            return vo;
        }).toList();
        return PageResult.of(total, page, size, vos);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminOrderDetailVO detail(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        MemberBriefVO member = memberQueryService.brief(order.getMemberId());

        AdminOrderDetailVO vo = new AdminOrderDetailVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setMemberId(order.getMemberId());
        vo.setMemberUsername(member == null ? null : member.getUsername());
        vo.setMemberName(member == null ? null : member.getNickname());
        vo.setMemberPhone(member == null ? null : member.getPhone());
        vo.setOrderStatus(order.getOrderStatus());
        vo.setPayStatus(order.getPayStatus());
        vo.setStatusText(OrderTexts.orderStatus(order.getOrderStatus()));
        vo.setTotalAmount(order.getTotalAmount());
        vo.setFreightAmount(order.getFreightAmount());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setUserRemark(order.getUserRemark());
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhone(order.getReceiverPhone());
        vo.setReceiverFullAddress(order.getReceiverFullAddress());
        vo.setLogisticsCompany(order.getLogisticsCompany());
        vo.setLogisticsNo(order.getLogisticsNo());
        vo.setCreateTime(order.getCreateTime());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setFinishTime(order.getFinishTime());
        vo.setItems(orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderNo, orderNo).orderByAsc(OrderItem::getId)).stream()
                .map(i -> {
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
        return vo;
    }
}