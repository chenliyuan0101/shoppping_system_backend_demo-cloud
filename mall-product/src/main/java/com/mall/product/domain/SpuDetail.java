package com.mall.product.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SPU 详情(1:1)，对应 pms_spu_detail。
 * images/params 为 MySQL json 列，实体用 String 承载(JsonKit 负责与数组互转)。
 */
@Data
@TableName("pms_spu_detail")
public class SpuDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spuId;

    private String description;

    /** json: ["url1","url2"] */
    private String images;

    /** json: [{"name":"屏幕","value":"6.1英寸"}] */
    private String params;

    private String detailHtml;

    private LocalDateTime updateTime;
}
