package com.mall.content.dto;

import com.mall.content.support.dto.CategoryNode;
import com.mall.content.support.dto.ProductListItemVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 首页聚合数据(见《接口文档.md》2.2 响应结构)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeData {

    private List<BannerVO> banners;
    private List<NoticeVO> notices;
    private List<CategoryNode> categories;
    private List<ProductListItemVO> hotProducts;
    private List<ProductListItemVO> newProducts;
}
