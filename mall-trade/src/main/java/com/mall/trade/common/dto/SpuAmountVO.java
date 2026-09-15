package com.mall.trade.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "某个 SPU 的销售金额"（域间契约）：销售额榜单的一条。
 *
 * <p>为什么不直接返回 {@code Map<spuId, amount>}：榜单是<b>有序</b>的（按金额倒序、只取前 N），
 * Map 表达不了顺序；而且列表形状将来可以直接对应一个内置端点的响应体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpuAmountVO {

    private Long spuId;

    /** 已支付订单里该 SPU 的成交金额合计（分） */
    private long amount;
}
