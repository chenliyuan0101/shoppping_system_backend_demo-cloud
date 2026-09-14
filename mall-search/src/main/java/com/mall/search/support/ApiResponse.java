package com.mall.search.support;

import lombok.Data;

/**
 * 统一响应体：{ code, message, data }
 * 约定(见《接口文档.md》1.3)：HTTP 恒为 200，业务结果以 code 为准；
 * 0=成功，非 0 见错误码(400/401/403/404/409/429/500)。
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
