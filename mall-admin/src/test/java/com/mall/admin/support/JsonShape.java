package com.mall.admin.support;

import com.jayway.jsonpath.JsonPath;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * JSON 键路径工具：把响应展开成"键路径集合"，用于 P7 §3 的<b>降级判据</b>
 * （"缺项时响应结构不变"= 键路径集合与正常时一致）。
 *
 * <p><b>口径与项目里已有的指纹法逐字一致</b>：{@code .dsh-notes/p6-c1-baseline.ps1} 的
 * {@code KeyPaths} 函数对数组的处理是"记一条 {@code prefix[]}，只递归第一个元素（空数组不递归）"。
 * 本类照抄这个口径（数组写成 {@code prefix[*]}），这样测试里的结论与 C1 基线可对齐比较。
 *
 * <h2>为什么还要额外提供"去掉元素键"的视图</h2>
 * 数组为空时，元素键（{@code $.data[*].date} 之类）**天然不存在**——这不是实现漂移，而是
 * "缺的那部分回空数组"这条要求本身的数学后果：一个空数组没有元素，也就没有元素的键路径。
 * 因此断言分两步，两步都要过：
 * <ol>
 *   <li>{@link #withoutElementPaths(Set)}：**不含元素键**的键路径集合必须完全相同
 *       （信封 {@code code/message/data}、{@code data[*]} 本身、以及所有标量键）；</li>
 *   <li>{@link #elementPaths(Set)}：元素键集合在"列表非空"时必须等于契约集合
 *       （{@code TrendItem} 3 个 / {@code TopItem} 4 个）——空数组时它必然为空，
 *       由调用方按"空数组"这一事实单独断言。</li>
 * </ol>
 * 把这两步分开写，是为了**不把判据悄悄放宽**：如果只是"两个集合相等"了事，
 * 一个把 {@code data} 从数组改成对象的退化就会被放过。
 */
public final class JsonShape {

    private JsonShape() {
    }

    /** 全部键路径；数组下标统一写成 {@code [*]}（与 p6-c1-baseline.ps1 的 {} 同义） */
    public static Set<String> keyPaths(String json) {
        Set<String> paths = new LinkedHashSet<>();
        collect(JsonPath.parse(json).json(), "$", paths);
        return paths;
    }

    /** 去掉"数组元素的键"（即含 {@code [*].} 的路径）后的集合 —— 空数组与满数组在这里必须一致 */
    public static Set<String> withoutElementPaths(Set<String> keyPaths) {
        Set<String> out = new LinkedHashSet<>();
        for (String path : keyPaths) {
            if (!path.contains("[*].")) {
                out.add(path);
            }
        }
        return out;
    }

    /** 数组元素的键（把 {@code $.data[*].date} 归一成 {@code date}），用于比对"元素契约" */
    public static Set<String> elementPaths(Set<String> keyPaths) {
        Set<String> out = new LinkedHashSet<>();
        for (String path : keyPaths) {
            int idx = path.indexOf("[*].");
            if (idx >= 0) {
                out.add(path.substring(idx + 4));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object node, String prefix, Set<String> sink) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String path = prefix + "." + entry.getKey();
                sink.add(path);
                collect(entry.getValue(), path, sink);
            }
        } else if (node instanceof Iterable<?> list) {
            sink.add(prefix + "[*]");
            for (Object item : list) {
                // 与脚本一致：只递归第一个元素（避免与元素个数耦合）
                collect(item, prefix + "[*]", sink);
                break;
            }
        }
    }
}
