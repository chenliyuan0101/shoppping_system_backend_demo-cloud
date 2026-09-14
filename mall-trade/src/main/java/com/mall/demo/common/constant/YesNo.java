package com.mall.demo.common.constant;

/**
 * 「0 否 / 1 是」型标志位列的统一定义。
 *
 * <p>覆盖列：{@code ums_address.is_default}(0否 1是)、{@code ums_cart_item.checked}(0否 1是)、
 * {@code ums_notification.is_read}(0未读 1已读)、{@code pms_spu.recommended}(0否 1是)。
 *
 * <p>这类列在 Java 侧常以 {@code boolean} 出入参，落库前用 {@code flag ? YES : NO} 转换，避免裸写 0/1。
 */
public final class YesNo {

    public static final int NO = 0;
    public static final int YES = 1;

    private YesNo() {
    }
}
