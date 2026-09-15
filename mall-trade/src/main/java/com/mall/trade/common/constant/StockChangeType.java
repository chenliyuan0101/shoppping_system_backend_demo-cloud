package com.mall.trade.common.constant;

/**
 * 库存变动类型 {@code pms_sku_stock_log.change_type}：
 * 1 下单扣减 / 2 用户取消回补 / 3 超时取消回补 / 4 退款回补 / 5 手动调整。
 *
 * <p>{@code delta} 正=增加、负=扣减；{@link #MANUAL_ADJUST} 的 {@code operator_id} 为后台管理员。
 */
public final class StockChangeType {

    public static final int ORDER_DEDUCT = 1;
    public static final int CANCEL_RESTORE = 2;
    public static final int TIMEOUT_RESTORE = 3;
    public static final int REFUND_RESTORE = 4;
    public static final int MANUAL_ADJUST = 5;

    private StockChangeType() {
    }
}
