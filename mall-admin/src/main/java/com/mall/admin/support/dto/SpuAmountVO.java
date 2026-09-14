package com.mall.admin.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "某个 SPU 的销售金额"：单体 {@code com.mall.demo.common.dto.SpuAmountVO} 的契约快照
 * （也是 {@code GET /internal/v1/stat/top-amount} 的元素形状）。
 *
 * <p>为什么不是 {@code Map<spuId, amount>}（单体注释的原话）：榜单是**有序**的
 * （按金额倒序、只取前 N），Map 表达不了顺序。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpuAmountVO {

    private Long spuId;

    /** 已支付订单里该 SPU 的成交金额合计（分） */
    private long amount;
}
