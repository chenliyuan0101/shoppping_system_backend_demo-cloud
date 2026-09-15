package com.mall.trade.common.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 后台商品详情(SPU + 详情 + SKU 回显)。
 *
 * <p><b>P6-4 起位于共享内核（{@code common.dto}）</b>：它是<b>跨服务契约</b>的返回体
 * ——{@code ProductAdminClient}（在 {@code common.client}）要反序列化 product 返回的 JSON，
 * 而共享内核**不允许依赖业务域**（ArchUnit 规则 B6）。原来它在 {@code pms.dto}，
 * 于是"后台薄转发"一落地就报 2 条 B6 违规（`ProductAdminClient -> pms.dto.ProductDetailVO`）。
 *
 * <p>⚠️ **字段名与声明顺序都不许动**：Jackson 按声明顺序输出，而 C1 要求对外 JSON 逐字不变
 * （判据：`p6-c1-baseline.ps1` 的 `admin-product-detail` 指纹 + `p6-4-admin-write-probe.ps1` 的商品 CRUD 全链路）。
 *
 * <p>⚠️ 搬移时**删掉**了原来的 {@code SkuVO.of(com.mall.trade.pms.domain.Sku)} 工厂：
 * 它是"实体 → VO"的映射，属业务域知识；留在共享内核会让 {@code common} 反向依赖 {@code pms.domain}
 * （又一个 B6 违规），而且它**已经没有调用者**（原调用方 `ProductPortalServiceImpl` 已随 D7 删除）。
 * 将来若商品域还需要这个映射，请放在**商品域内**（或 product 服务里）。
 */
@Data
public class ProductDetailVO {

    private Long spuId;
    private Long categoryId;
    private String categoryName;
    private Long brandId;
    private String brandName;
    private String title;
    private String subtitle;
    private String mainImage;
    private Integer status;
    private Integer recommended;
    private Integer sales;
    private String description;
    private String detailHtml;
    private List<String> images;
    private List<Map<String, Object>> params;
    private List<SkuVO> skus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @Data
    public static class SkuVO {
        private Long skuId;
        private String skuCode;
        private List<Map<String, Object>> specValues;
        private String image;
        private Long price;
        private Long originalPrice;
        private Integer stock;
        private Integer status;
    }
}
