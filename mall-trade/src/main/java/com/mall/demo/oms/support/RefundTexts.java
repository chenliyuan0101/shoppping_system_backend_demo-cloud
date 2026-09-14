package com.mall.demo.oms.support;

import com.mall.demo.common.constant.RefundStatus;

/**
 * 售后状态/类型文案：状态 0 待处理 / 1 处理中(等待回寄) / 2 已完成 / 3 已拒绝 / 4 已取消；类型 1 仅退款 / 2 退货退款。
 */
public final class RefundTexts {

    public static final int TYPE_ONLY_MONEY = 1;
    public static final int TYPE_RETURN_GOODS = 2;

    private RefundTexts() {
    }

    public static String status(Integer status) {
        return switch (status == null ? -1 : status) {
            case RefundStatus.PENDING -> "待处理";
            case RefundStatus.AGREED -> "处理中";
            case RefundStatus.FINISHED -> "已完成";
            case RefundStatus.REJECTED -> "已拒绝";
            case RefundStatus.CANCELED -> "已取消";
            default -> "未知";
        };
    }

    public static String type(Integer refundType) {
        return refundType != null && refundType == TYPE_RETURN_GOODS ? "退货退款" : "仅退款";
    }
}
