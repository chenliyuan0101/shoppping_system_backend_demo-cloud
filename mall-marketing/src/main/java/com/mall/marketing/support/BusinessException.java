package com.mall.marketing.support;

import lombok.Getter;

/**
 * 业务异常：code 与《接口文档.md》1.4 错误码一致。
 *
 * <p>营销域的对外错误文案（C1 基线，方案 §4.3）**逐字保留**，本批已落地的 5 条是：
 * 400「优惠券不可用」、409「优惠券已被使用或失效」、400「优惠券已过期」、
 * 400「优惠券已停用」、400「未满足优惠券使用门槛」。
 * 这些文案由 {@code CouponQueryService}/{@code CouponCommandService} 抛出，
 * 经 {@link GlobalExceptionHandler} 变成 HTTP 200 + {@code code}，
 * trade 侧调用时再原样透传给用户——**一个字都不能改**（改了前端提示就变）。
 *
 * <p>另外两个码是本服务自己的门禁：403（{@code /internal/**} 鉴权失败）、401（网关身份不可信）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
