package com.mall.search.dto;

import java.util.List;

/**
 * 商品域返回的"索引文档集"（{@code POST /internal/v1/product/index-docs} 的 {@code data}）。
 *
 * <p>三种取法共用同一形状（规格 §4）：
 * <ul>
 *   <li><b>按 spuIds</b>：只包含**在架且未删除**的那些 spu 的文档。
 *       ⚠️ 调用方要拿"请求的 id 集合 − 返回的 id 集合"算出**需要从索引删除**的 id
 *       （现状 {@code syncProduct(spuId)} 的语义：查不到/非在架 → {@code deleteProduct}）；</li>
 *   <li><b>按 brandId</b>：该品牌下在架商品的文档（{@code syncByBrand} 用）；</li>
 *   <li><b>分页全量</b>：按 {@code spuId} 升序的一页（{@code reindex} 用），
 *       此时 {@link #totalInShelf} 才有意义（= 在架 spu 总数 = 期望文档数）。</li>
 * </ul>
 *
 * <p>⚠️ 本类是**本服务自持的副本**（不引共享 jar）：product 侧有一份同形状的 DTO，
 * 字段名必须逐字一致（契约靠"逐字相同 + 契约测试"守，见方案 §2.8/§4.10）。
 *
 * @param totalInShelf 在架 spu 总数（全量分页模式下用于校验"写入数 == 期望数"）
 * @param docs         索引文档（字段与 ES mapping 的 12 个字段一一对应）
 */
public record IndexDocsResult(long totalInShelf, List<ProductSearchDoc> docs) {

    public IndexDocsResult {
        docs = docs == null ? List.of() : List.copyOf(docs);
    }

    public boolean isEmpty() {
        return docs.isEmpty();
    }

    public int size() {
        return docs.size();
    }
}
