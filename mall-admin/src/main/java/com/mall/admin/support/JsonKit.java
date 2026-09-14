package com.mall.admin.support;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON 工具（Spring Boot 4 内置 Jackson 3 / {@code tools.jackson}）。
 *
 * <p>本服务的用途只有一处：管理端状态缓存 {@code mall:cache:admin:status:{id}} 的载荷序列化/反序列化
 * —— 那份 JSON 是**跨服务契约**（网关 {@code AdminIdentityFilter} 用正则从里面取 {@code status}），
 * 所以字段名必须与 {@link com.mall.admin.support.dto.AdminStatusVO} 的声明名逐字一致。
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

    /** 字符串 → 目标类型（缓存反序列化用） */
    public static <T> T toObject(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("json parse " + type.getSimpleName() + " failed", e);
        }
    }

    /** 泛型集合/分页读取（缓存反序列化用，如 List<XxxVO>） */
    public static <T> T toObject(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("json parse type failed", e);
        }
    }
}
