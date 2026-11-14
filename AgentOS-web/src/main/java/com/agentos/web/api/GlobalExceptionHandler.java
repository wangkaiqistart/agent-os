package com.agentos.web.api;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 统一异常处理：所有错误都转成 {@link ApiResponse} 信封，见技术方案 7.1、7.4。
 *
 * <ul>
 *   <li>{@link AgentOSException}：按错误码映射 HTTP 状态码，消息原样返回。
 *   <li>Spring MVC 自身的异常（参数校验、JSON 解析、404、405 等）：保留原状态码，换成统一信封。
 *   <li>其他异常：一律 500，只返回固定文案，细节只进日志，避免把内部信息泄露给调用方。
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentOSException.class)
    public ResponseEntity<ApiResponse<Void>> handleAgentOS(AgentOSException ex) {
        // 业务层已经给出稳定的 ErrorCode，这里只负责把它翻译成 HTTP 状态码。
        HttpStatus status = toHttpStatus(ex.getErrorCode());
        if (status.is5xxServerError()) {
            log.error("请求失败：{}", ex.getErrorCode(), ex);
        } else {
            log.debug("请求被拒绝：{} {}", ex.getErrorCode(), ex.getMessage());
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        // 未知异常的细节只写服务端日志，不能把数据库、路径或密钥信息返回给客户端。
        log.error("未处理的异常", ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "内部错误"));
    }

    /**
     * 错误码到 HTTP 状态码的唯一映射处。
     *
     * <p>这里不写 default：Java 的穷尽 switch 会在新增 ErrorCode 后提醒开发者补充映射。
     */
    static HttpStatus toHttpStatus(ErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SESSION_BUSY -> HttpStatus.CONFLICT;
            case PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Spring MVC 内置异常最终都经过这里，改写成统一信封，状态码保持不变。
     *
     * <p>例如 JSON 解析失败、参数校验失败、找不到路径和 HTTP 方法不支持，
     * 都不是业务代码主动抛出的 AgentOSException，所以要覆盖父类的处理入口。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ErrorCode code;
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
            code = ErrorCode.NOT_FOUND;
        } else if (statusCode.is4xxClientError()) {
            code = ErrorCode.INVALID_ARGUMENT;
        } else {
            code = ErrorCode.INTERNAL_ERROR;
            log.error("请求处理失败", ex);
        }
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(ApiResponse.error(code, describe(ex, statusCode)));
    }

    private static String describe(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof MethodArgumentNotValidException invalid) {
            // 参数校验异常包含多个字段错误；把它们合并成调用方能直接修正的文字。
            return invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + " " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        }
        // 其他内置异常的 message 可能带内部类名或实现细节，只返回 HTTP 标准短语。
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.getReasonPhrase() : "请求失败";
    }
}
