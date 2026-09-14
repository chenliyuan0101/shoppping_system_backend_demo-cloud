package com.mall.demo.common.dto;

import lombok.Data;

/**
 * {@code /internal/v1/marketing/coupon/lock} 的响应载荷：{@code {"locked": true|false}}。
 *
 * <p>本服务的契约副本（不建共享 jar：契约靠"逐字相同 + 契约测试"守）。
 * 用对象而不是裸 boolean，是为了让营销域以后能加字段而不破坏调用方。
 */
@Data
public class CouponLockResultVO {

    private boolean locked;
}
