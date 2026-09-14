package com.mall.product.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品 SPU，对应 pms_spu。
 */
@Data
@TableName("pms_spu")
public class Spu {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long categoryId;

    private Long brandId;

    private String title;

    private String subtitle;

    private String mainImage;

    /** 0下架 1上架 */
    private Integer status;

    /** 首页推荐 0否 1是 */
    private Integer recommended;

    private Integer sales;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
