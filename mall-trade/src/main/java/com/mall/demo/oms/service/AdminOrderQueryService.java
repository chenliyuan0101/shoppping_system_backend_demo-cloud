package com.mall.demo.oms.service;

import com.mall.demo.common.PageResult;
import com.mall.demo.oms.dto.AdminOrderDetailVO;
import com.mall.demo.oms.dto.AdminOrderListVO;

import java.time.LocalDateTime;

/**
 * 后台订单查询(发货/关闭等动作在 OrderService 提供)。
 */
public interface AdminOrderQueryService {

    /**
     * 后台订单分页。
     *
     * @param createTimeStart 下单时间起(含)，null 不限
     * @param createTimeEnd   下单时间止(不含)，null 不限
     */
    PageResult<AdminOrderListVO> page(String orderNo, String memberKeyword, Integer status, Integer payStatus,
                                      LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                      long pageNum, long pageSize);

    AdminOrderDetailVO detail(String orderNo);
}
