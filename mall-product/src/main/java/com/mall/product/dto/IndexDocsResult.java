package com.mall.product.dto;

import java.util.List;

/**
 * 索引文档集（{@code POST /internal/v1/product/index-docs} 的响应体；P6-2 新增）。
 *
 * <p>这是**商品域向检索域交付"索引文档内容"的契约**：字段与
 * {@code mall_search} 侧的同名记录、以及 ES 索引 {@code mall_product} 的 12 个 mapping 字段一一对应。
 * 两侧各持一份副本（不引共享 jar），靠"逐字相同 + 契约测试"守（方案 §2.8/§4.10）。
 *
 * <p>⚠️ 三种取法（按 spuIds / 按 brandId / 全量分页）共用这一个形状：
 * <ul>
 *   <li>按 spuIds：**只包含在架且未删除**的 spu 的文档。调用方用
 *       "请求的 id − 返回的 id"算出**要删除**的 id（这正是拆分前
 *       {@code ProductSearchServiceImpl#syncProduct} 里 {@code spu==null || status!=1 → 删除} 的等价物）；</li>
 *   <li>按 brandId：该品牌下在架商品的文档（拆分前 {@code syncByBrand} 的口径）；</li>
 *   <li>分页：按 {@code spuId} 升序的一页（**顺序稳定**，调用方可安全逐页翻）。</li>
 * </ul>
 *
 * @param totalInShelf 当前**在架**（{@code status=1 AND deleted=0}）的 spu 总数；与三种取法都无关，
 *                     它的用途是"重建完成后校验写入数 == 期望数"
 * @param docs         索引文档（字段见 {@link ProductSearchDoc}）
 */
public record IndexDocsResult(long totalInShelf, List<ProductSearchDoc> docs) {

    public IndexDocsResult {
        docs = docs == null ? List.of() : List.copyOf(docs);
    }

    public int size() {
        return docs.size();
    }
}
