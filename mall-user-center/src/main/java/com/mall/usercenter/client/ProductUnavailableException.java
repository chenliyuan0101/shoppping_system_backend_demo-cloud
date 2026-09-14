package com.mall.usercenter.client;

/**
 * 商品域不可用（超时 / 连不上 / 5xx / 业务码非 0 / 端点不存在）。
 *
 * <p>与 mall-content 的 {@code ProductFeedUnavailableException} 同一套口径，刻意做成**独立异常类型**：
 * 降级决策只能针对"下游不可用"这一类情况生效，不能顺手把本服务自己的 bug（NPE、序列化错）
 * 也吞成"购物车里没有商品"——那会让真问题在日志里消失（《微服务改造方案.md》§2.8 降级原则）。
 *
 * <p>因此本客户端的方法**不返回 null 表示"下游挂了"**：null 只表示"商品域明确回答没有这个 SKU/SPU"
 * （那是业务事实，调用方按 400「商品不存在或已下架」处理）；下游不可用则抛本异常，
 * 由全局异常处理器转成 500「系统繁忙，请稍后重试」。
 */
public class ProductUnavailableException extends RuntimeException {

    public ProductUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProductUnavailableException(String message) {
        super(message);
    }
}
