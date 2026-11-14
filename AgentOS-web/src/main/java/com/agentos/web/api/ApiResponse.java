package com.agentos.web.api;

import com.agentos.core.error.ErrorCode;
import java.time.Instant;

/**
 * REST 统一响应信封，成功与错误共用，见技术方案 7.1。
 *
 * @param code 成功为 {@code OK}，失败为 {@link ErrorCode} 的名字
 * @param message 给调用方看的说明
 * @param data 业务数据，失败时为 {@code null}
 * @param timestamp 响应生成时间
 *
 * <p>“信封”表示接口外层格式固定，业务数据放在 {@code data} 中。调用方不用为每个接口猜测
 * 成功和失败的 JSON 结构。
 */
public record ApiResponse<T>(String code, String message, T data, Instant timestamp) {

    public static final String OK = "OK";

    /** 构造一个成功响应，保留调用方传入的业务数据。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK, "success", data, Instant.now());
    }

    /** 构造一个错误响应；错误时刻意把 data 设为 null。 */
    public static <T> ApiResponse<T> error(ErrorCode code, String message) {
        return new ApiResponse<>(code.name(), message, null, Instant.now());
    }
}
