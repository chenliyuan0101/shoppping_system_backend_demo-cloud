package com.mall.trade.oms.dto;

import lombok.Data;

/**
 * 结算/订单中的地址信息(快照展示)。
 */
@Data
public class AddressInfoVO {

    private Long id;
    private String receiverName;
    private String receiverPhone;
    private String fullAddress;
}
