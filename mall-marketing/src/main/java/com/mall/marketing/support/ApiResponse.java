package com.mall.marketing.support;

import lombok.Data;

/**
 * 统一响应体：{ code, message, data }
 * 约定(见《接口文档.md》1.3)：HTTP 恒为 200，业务结果以 code 为准；
 * 0=成功，非 0 见错误码(400/401/403/404/409/429/500)。
 *
 * <p>本服务自持副本（与 mall-user-center / mall-content / mall-review / 单体逐字相同）：
 * 拆服务不共享 jar，契约靠"逐字相同 + 契约测试"守（方案 §2.8/§4.10）。
 * 券的 5 条错误文案（400/409）就是靠这个形状返回给 trade 的——
 * trade 侧要按 code/文案原样透传给用户，所以字段名与 HTTP 恒 200 都是契约。
 */
@Data
public class ApiResponse<T> {

    public static final int SUCCESS = 0;

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(SUCCESS);
        resp.setMessage("ok");
        resp.setData(data);
        return resp;
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(code);
        resp.setMessage(message);
        return resp;
    }
}
