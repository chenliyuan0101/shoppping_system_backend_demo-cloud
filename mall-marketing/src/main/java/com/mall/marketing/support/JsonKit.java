package com.mall.marketing.support;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具(Spring Boot 4 内置 Jackson 3 / tools.jackson)。
 *
 * <p>本服务自持副本（与 review 那份逐字相同）。⚠️ 券域的两张表**没有 JSON 列**，
 * 本批也没有任何调用点——它是共享内核里"每个服务都得有"的一件工具，
 * 放进来是为了批次 3 的"发放记录"（records 的券/会员组合视图）与批次 2 的缓存
 * 不要各自手写一份 ObjectMapper 配置。**它不是摆设，但确实还没接线**（如实记在这里）。
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

    /** 字符串数组（评论晒图、规格图这类形状） */
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

    /** 泛型集合/分页读取(缓存反序列化用) */
    public static <T> T toObject(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("json parse type failed", e);
        }
    }
}
