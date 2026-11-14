package com.agentos.provider.chat;

import com.agentos.core.provider.FailureKind;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 把模型调用抛出的异常归类为 {@link FailureKind}，并判断能否重试、重试前要等多久。
 *
 * <p>只做分类，不做任何等待、重试或记录：这些由 {@link DefaultProviderService} 负责。 本类也是错误摘要的唯一出口：摘要只由失败分类、HTTP
 * 状态码和经过过滤的 type / code 拼成， 不调用异常的 {@code getMessage()}，不含原始响应体，避免把响应里的密钥或 Prompt 带进记录和日志（research R7）。
 */
public final class FailureClassifier {

    /** Retry-After 缺失、为负数或无法解析时的等待时间（FR-018）。 */
    static final Duration DEFAULT_RETRY_WAIT = Duration.ofMillis(500);

    /** 可重试的 5xx 状态码；501、505 等表示服务端不支持，重试没有意义。 */
    private static final Set<Integer> RETRYABLE_SERVER_STATUSES = Set.of(500, 502, 503, 504);

    /** Retry-After 的数字太大（超过 9 位）时按一天处理，后续的预算判断会直接放弃重试。 */
    private static final Duration HUGE_WAIT = Duration.ofDays(1);

    private static final int MAX_SECONDS_DIGITS = 9;

    /** 沿异常链最多向下找几层，防止循环引用。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    /**
     * type / code 只接受字母、数字、下划线和点，且最长 64 个，服务端返回的内容属于不可信输入。
     *
     * <p>刻意不含连字符：常见的错误码（rate_limit_exceeded、invalid_api_key、Arrearage）都没有它，
     * 而 {@code sk-...} 这类密钥一定有，这样服务端把密钥回显进 type / code 时会落到占位符。
     */
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.]{1,64}");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static final String INVALID_TOKEN = "invalid";

    private FailureClassifier() {}

    /**
     * 一次失败的分类结果。
     *
     * @param kind 失败分类
     * @param retryable 是否属于可重试的暂时性故障
     * @param httpStatus HTTP 状态码；连接错误、取消等没有状态码时为 null
     * @param retryAfter 重试前建议等待的时间；不可重试时为 null
     * @param summary 脱敏后的错误摘要，可以直接写入记录和异常消息
     */
    public record Classification(
            FailureKind kind,
            boolean retryable,
            Integer httpStatus,
            Duration retryAfter,
            String summary) {}

    /** 响应没有候选结果等协议层问题的固定分类，不重试。 */
    public static Classification protocol(String summary) {
        return new Classification(FailureKind.PROTOCOL, false, null, null, summary);
    }

    /** 调用方取消（线程被中断）的固定分类，不重试。 */
    public static Classification cancelled() {
        return new Classification(FailureKind.CANCELLED, false, null, null, "CANCELLED 调用被取消");
    }

    /**
     * 对模型调用抛出的异常分类。
     *
     * <p>判断顺序：先看调用线程是否被中断（取消优先于一切），再沿异常链找 SDK 异常。 找不到 SDK 异常时按 PROTOCOL 处理，不重试。
     */
    public static Classification classify(Throwable error) {
        if (isCancelled(error)) {
            return cancelled();
        }
        Throwable sdkError = findSdkException(error);
        if (sdkError instanceof OpenAIServiceException service) {
            return fromStatus(service);
        }
        if (sdkError instanceof OpenAIIoException || hasCause(error, TimeoutException.class)) {
            // 连接失败和读超时走前一种；流式调用到截止时间被取消订阅时是后一种。
            // 线程被中断的情况上面已经排除。
            return new Classification(
                    FailureKind.CONNECTION, true, null, DEFAULT_RETRY_WAIT, "CONNECTION 连接失败或读取超时");
        }
        return protocol("PROTOCOL 响应无法处理");
    }

    /**
     * 按状态码分类。SDK 把 400 / 401 / 403 / 404 / 422 / 429 / 5xx 映射成不同的异常类，
     * 其余状态码统一是 UnexpectedStatusCodeException，所以直接看状态码更简单，结果与 research R6 的表一致。
     */
    private static Classification fromStatus(OpenAIServiceException service) {
        int status = service.statusCode();
        FailureKind kind;
        boolean retryable;
        if (status == 429) {
            kind = FailureKind.RATE_LIMITED;
            retryable = true;
        } else if (status >= 500) {
            kind = FailureKind.SERVER_ERROR;
            retryable = RETRYABLE_SERVER_STATUSES.contains(status);
        } else if (status == 401 || status == 403) {
            kind = FailureKind.AUTH;
            retryable = false;
        } else if (status >= 400) {
            kind = FailureKind.INVALID_REQUEST;
            retryable = false;
        } else {
            // 2xx / 3xx 不应该变成异常，出现说明协议层有问题。
            kind = FailureKind.PROTOCOL;
            retryable = false;
        }
        Duration retryAfter =
                retryable ? parseRetryAfter(service.headers().values("Retry-After")) : null;
        return new Classification(
                kind, retryable, status, retryAfter, summarize(kind, status, service));
    }

    /**
     * 解析 Retry-After 的秒数。缺失、负数、非数字（包括 HTTP-date，暂不支持）一律按 500 毫秒处理。
     */
    static Duration parseRetryAfter(List<String> values) {
        if (values.isEmpty()) {
            return DEFAULT_RETRY_WAIT;
        }
        String text = values.getFirst().trim();
        if (!DIGITS.matcher(text).matches()) {
            return DEFAULT_RETRY_WAIT;
        }
        if (text.length() > MAX_SECONDS_DIGITS) {
            return HUGE_WAIT;
        }
        return Duration.ofSeconds(Long.parseLong(text));
    }

    private static String summarize(FailureKind kind, int status, OpenAIServiceException service) {
        StringBuilder summary = new StringBuilder();
        summary.append(kind.name()).append(" HTTP ").append(status);
        appendToken(summary, "type", service.type());
        appendToken(summary, "code", service.code());
        return summary.toString();
    }

    private static void appendToken(StringBuilder summary, String name, Optional<String> raw) {
        if (raw.isEmpty()) {
            return;
        }
        String value = SAFE_TOKEN.matcher(raw.get()).matches() ? raw.get() : INVALID_TOKEN;
        summary.append(' ').append(name).append('=').append(value);
    }

    /**
     * 判断是不是调用方取消。
     *
     * <p>只看线程的中断标志和异常链里的 {@link InterruptedException}。不能把 {@code InterruptedIOException}
     * 当成取消：HTTP 客户端的读超时也是它，超时应该按 CONNECTION 重试。
     */
    private static boolean isCancelled(Throwable error) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Throwable findSdkException(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof OpenAIException) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }
}
