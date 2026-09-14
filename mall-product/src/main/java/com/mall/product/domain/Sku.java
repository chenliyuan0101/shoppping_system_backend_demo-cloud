package com.mall.product.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品 SKU(可下单最小单元，含库存)，对应 pms_sku。
 * spec_values 为 json 列，实体用 String 承载。
 */
@Data
@TableName("pms_sku")
public class Sku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spuId;

    private String skuCode;

    /** json: [{"name":"颜色","value":"黑色"}] */
    private String specValues;

    private String image;

    /** 售价(分) */
    private Long price;

    /** 划线价(分) */
    private Long originalPrice;

    /** 可售库存 */
    private Integer stock;

    private Integer sales;

    /** 0停用 1启用 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
