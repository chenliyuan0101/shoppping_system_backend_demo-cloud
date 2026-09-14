package com.mall.product.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 品牌，对应 pms_brand。
 */
@Data
@TableName("pms_brand")
public class Brand {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String logo;

    private Integer sort;

    /** 0停用 1启用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
