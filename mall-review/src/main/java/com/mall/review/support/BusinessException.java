package com.mall.review.support;

import lombok.Getter;

/**
 * 业务异常：code 与《接口文档.md》1.4 错误码一致。
 *
 * <p>P4 的对外错误文案要**逐字保留**（方案 §4.5 兼容性要点）：
 * 404「订单不存在」、409「订单完成后才能评价」、409「已超过 N 天可评价期限」、
 * 400「评价条目不属于该订单」、400「同一订单明细不能重复评价」、409「该商品已评价」。
 * 这些文案的落地在评价业务搬过来的那一批，本批只有内部接口用它表达 403/401。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
