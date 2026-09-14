package com.mall.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 后台商品(SPU)新增/更新请求。
 * 约定：skus 全量提交；更新时以 skuCode 匹配，未匹配的旧 SKU 删除(简化模型)。
 */
@Data
@Schema(description = "商品新增/更新请求（请求体完整示例见 POST /api/admin/product 的 Examples）")
public class AdminProductSaveRequest {

    @Schema(description = "商品类目ID(须存在)", example = "12")
    private Long categoryId;

    @Schema(description = "品牌ID(可空)", example = "1")
    private Long brandId;

    @Schema(description = "商品标题", example = "无线降噪耳机 Pro")
    @NotBlank(message = "请输入商品标题")
    private String title;

    @Schema(description = "副标题/卖点", example = "主动降噪 · 30小时续航")
    private String subtitle;

    @Schema(description = "主图URL", example = "http://localhost:9000/mall/seed/earphone-pro.jpg")
    private String mainImage;

    @Schema(description = "一句话卖点描述", example = "示例商品描述")
    private String description;

    @Schema(description = "图文详情富文本 HTML", example = "<p>图文详情</p>")
    private String detailHtml;

    @Schema(description = "图集 URL 数组", example = "[\"http://localhost:9000/mall/seed/earphone-pro.jpg\"]")
    private List<String> images;

    @Schema(description = "商品参数 [{name,value}]")
    private List<ParamItem> params;

    @Schema(description = "规格 SKU 列表(至少 1 个)")
    @NotEmpty(message = "至少需要一个 SKU")
    private List<SkuItem> skus;

    @Data
    @Schema(description = "商品参数项")
    public static class ParamItem {

        @Schema(description = "参数名", example = "续航")
        private String name;

        @Schema(description = "参数值", example = "30小时")
        private String value;
    }

    @Data
    @Schema(description = "SKU 项(金额单位：分)")
    public static class SkuItem {

        @Schema(description = "SKU 编码(同商品内唯一)", example = "EP-BK")
        private String skuCode;

        @Schema(description = "规格值 [{name,value}]，如 颜色/黑", example = "[{\"name\":\"颜色\",\"value\":\"黑\"}]")
        private List<ParamItem> specValues;

        @Schema(description = "SKU 图(可空)", example = "http://localhost:9000/mall/seed/earphone-bk.jpg")
        private String image;

        @Schema(description = "售价(分)", example = "29900")
        private Long price;

        @Schema(description = "划线价(分)，可空", example = "39900")
        private Long originalPrice;

        @Schema(description = "库存", example = "50")
        private Integer stock;
    }
}
