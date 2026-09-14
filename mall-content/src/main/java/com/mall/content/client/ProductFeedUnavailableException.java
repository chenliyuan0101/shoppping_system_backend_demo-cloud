package com.mall.content.client;

/**
 * 商品域不可用（超时 / 连不上 / 5xx / 业务码非 0）。
 *
 * <p>刻意做成**独立的异常类型**而不是让调用方 catch 一切：
 * 降级决策只能针对"下游不可用"这一类情况生效，
 * 不能顺手把本服务自己的 bug（NPE、序列化错）也吞成"首页少了商品区块"——
 * 那会让真问题在日志里消失（§2.8 降级原则）。
 */
public class ProductFeedUnavailableException extends RuntimeException {

    public ProductFeedUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProductFeedUnavailableException(String message) {
        super(message);
    }
}
