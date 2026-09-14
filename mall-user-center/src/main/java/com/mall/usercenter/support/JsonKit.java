package com.mall.usercenter.support;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具(Spring Boot 4 内置 Jackson 3 / tools.jackson)。
 * 用于数据库 JSON 列(images/params/spec_values)与前端数组/对象的互转。
 */
public final class JsonKit {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonKit() {
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    /** 图片等字符串数组 */
    public static List<String> toImageList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("json parse list<string> failed", e);
        }
    }

    /** 参数/规格等 [{name,value}] 结构 */
    public static List<Map<String, Object>> toMapList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("json parse list<map> failed", e);
        }
    }

    /** 字符串 → 目标类型(缓存反序列化用) */
    public static <T> T toObject(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("json parse " + type.getSimpleName() + " failed", e);
        }
    }

    /** 泛型集合/分页读取(缓存反序列化用，如 List<CategoryNode>) */
    public static <T> T toObject(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("json parse type failed", e);
        }
    }

    /** 规格 JSON → 展示文本，如 "[{name:颜色,value:黑}]" → "黑"；多规格用 " / " 连接 */
    public static String toSpecText(String specJson) {
        return toMapList(specJson).stream()
                .map(m -> String.valueOf(m.getOrDefault("value", "")))
                .collect(java.util.stream.Collectors.joining(" / "));
    }
}
