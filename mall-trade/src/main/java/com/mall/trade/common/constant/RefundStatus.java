package com.mall.trade.common.constant;

/**
 * 售后单状态 {@code oms_refund.status}：
 * 0 待处理 / 1 已同意处理中(退货退款等待买家回寄) / 2 已完成(模拟退款成功) / 3 已拒绝 / 4 已取消(买家撤单)。
 *
 * <p>文本文案见 {@link RefundTexts#status(Integer)}。
 * 注意 DDL 注释当前只写到 "3已拒绝"，4已取消 仅在本类与代码中体现(见《数据库设计文档.md》待补)。
 */
public final class RefundStatus {

    public static final int PENDING = 0;
    public static final int AGREED = 1;
    public static final int FINISHED = 2;
    public static final int REJECTED = 3;
    public static final int CANCELED = 4;

    private RefundStatus() {
    }
}
