package com.mall.demo.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台品牌（{@code /api/admin/brand/**} 的返回体）。
 *
 * <p><b>为什么新增它、而不是继续用 {@code pms.domain.Brand}</b>（P6-4 的 D9 / B6 违规修复）：
 * P6-4 把后台品牌端点改成"单体薄转发到 product"后，{@code ProductAdminClient}（在共享内核 {@code common.client}）
 * 需要反序列化品牌 JSON，于是引入了 {@code ProductAdminClient -> pms.domain.Brand} 这条
 * <b>共享内核 → 业务域</b>的依赖，被 ArchUnit 规则 B6 拦下（"基线不许涨"）。
 * 处置沿用 P0/P5 的先例（`common/dto/AdminCouponVO` 去实体化）：**接口不再返回实体**，
 * 由共享内核里的这个 VO 承担契约。
 *
 * <p>⚠️ **字段名与声明顺序与 {@code pms.domain.Brand} 逐字相同**（`id,name,logo,sort,status,createTime,updateTime`）：
 * Jackson 按声明顺序输出，C1 要求 `/api/admin/brand/page`、`/api/admin/brand/list` 的 JSON **逐字不变**
 * （判据：`p6-c1-baseline.ps1` 的两条品牌指纹 + `p6-4-admin-write-probe.ps1` 的品牌 CRUD 全链路）。
 *
 * <p>⚠️ 这里**不带** {@code @TableName}/{@code @TableId}：它是契约 DTO，不是实体；实体仍留在商品域
 * （{@code pms.domain.Brand}，只被 mapper 与本域代码使用）。
 */
@Data
public class AdminBrandVO {

    private Long id;

    private String name;

    private String logo;

    private Integer sort;

    /** 0停用 1启用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
