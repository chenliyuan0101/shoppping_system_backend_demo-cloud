package com.mall.demo.oms.dto;

import lombok.Data;

/**
 * 结算明细项(服务端实时核算)。
 */
@Data
public class PreviewItemVO {

    private Long skuId;
    private Long spuId;
    private String title;
    private String skuName;
    private String image;
    /** 现价(分) */
    private Long price;
    private Integer quantity;
    /** 库存是否充足 */
    private Boolean stockEnough;
}
