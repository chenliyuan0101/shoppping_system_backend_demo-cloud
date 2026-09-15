package com.mall.trade.oms.dto;

import com.mall.trade.common.dto.CouponBriefVO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 结算页预览结果(不落单，金额服务端核算)。
 * 运费一期固定 0；优惠来自所选优惠券，coupons 返回可选券(用户勾选前默认不自动选)。
 */
@Data
@Builder
public class PreviewResult {

    private List<PreviewItemVO> items;
    private AddressInfoVO defaultAddress;
    private List<CouponBriefVO> coupons;
    private Long freightAmount;
    private Long discountAmount;
    private Long totalAmount;
    private Long payAmount;
}
