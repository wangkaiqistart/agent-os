package com.agentos.tool.http;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolCallLedger;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnExecutionBudget;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** HTTP工具；跳转、连接、响应头和分片正文共享同一个scope。 */
public final class HttpTools implements AutoCloseable {
    private final HttpToolProperties properties;
    private final com.agentos.tool.sandbox.ToolApprovalGate gate;
    private final HttpClient client;

    public HttpTools(HttpToolProperties properties, Sandbox sandbox) {
        this(properties, com.agentos.tool.sandbox.ToolApprovalGate.of(sandbox));
    }

    public HttpTools(
            HttpToolProperties properties, com.agentos.tool.sandbox.ToolApprovalGate gate) {
        this.properties = properties;
        this.gate = gate;
        client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(properties.timeout())
                        .build();
    }

    @Tool(
            name = "http_get",
            description =
                    "读取 HTTP/HTTPS 地址并返回响应正文。域名未预先授权时会请求用户确认（每次跳转到新域名也会），" + "用户拒绝时不要重复访问同一地址")
    public String http_get(
            @ToolParam(description = "完整 HTTP 或 HTTPS URL") String url, ToolContext context) {
        var supplied = (ToolExecutionScope) context.getContext().get(ToolContextKeys.TOOL_SCOPE);
        var ledger = (ToolCallLedger) context.getContext().get(ToolContextKeys.TOOL_CALL_LEDGER);
        var scope =
                supplied != null
                        ? supplied
                        : new ToolExecutionScope(
                                null,
                                "http_get",
                                (TurnExecutionBudget)
                                        context.getContext().get(ToolContextKeys.TURN_BUDGET),
                                properties.timeout(),
                                () -> {
                                    if (ledger != null) {
                                        ledger.checkpoint();
                                    }
                                });
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException invalid) {
            throw new SandboxViolationException("无效 HTTP 地址");
        }
        try {
            for (int redirects = 0; ; redirects++) {
                scope.checkpoint();
                gate.enforce(
                        new SandboxAction(ActionType.HTTP, uri.toString(), List.of("GET")),
                        context);
                var request =
                        HttpRequest.newBuilder(uri)
                                .GET()
                                .header("User-Agent", properties.userAgent())
                                .build();
                var response =
                        BoundedHttpIo.exchange(
                                client, request, scope, properties.maxResponseBytes());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    if (redirects >= properties.maxRedirects()) {
                        throw new SandboxViolationException("HTTP 重定向超过上限");
                    }
                    String location =
                            response.headers()
                                    .firstValue("Location")
                                    .orElseThrow(
                                            () -> new IllegalStateException("HTTP 重定向缺少 Location"));
                    try {
                        uri = uri.resolve(location);
                    } catch (RuntimeException invalid) {
                        throw new SandboxViolationException("无效重定向地址");
                    }
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP 状态码：" + response.statusCode());
                }
                return new String(response.body(), StandardCharsets.UTF_8);
            }
        } finally {
            if (supplied == null) {
                scope.close();
            }
        }
    }

    @Tool(
            name = "http_post",
            description =
                    "向 HTTP/HTTPS 地址提交UTF-8正文；不重试或跟随跳转，2xx仅表示传输成功。" + "域名未预先授权时会请求用户确认，用户拒绝时不要重复提交")
    public String http_post(
            @ToolParam(description = "完整HTTP或HTTPS地址") String url,
            @ToolParam(description = "UTF-8文本或JSON正文") String body,
            @ToolParam(description = "application/json（默认）或text/plain", required = false)
                    String contentType,
            ToolContext context) {
        var scope = (ToolExecutionScope) context.getContext().get(ToolContextKeys.TOOL_SCOPE);
        if (scope == null) {
            throw new IllegalStateException("HTTP提交缺少执行scope");
        }
        scope.checkpoint();
        String type = contentType == null ? "application/json" : contentType;
        if (!(type.equals("application/json") || type.equals("text/plain"))
                || body == null
                || body.length() > properties.maxRequestBytes()) {
            throw new IllegalArgumentException("INVALID_ARGUMENT：提交正文或类型无效");
        }
        byte[] bytes;
        try {
            var encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(body));
            if (encoded.remaining() > properties.maxRequestBytes()) {
                throw new IllegalArgumentException("TOO_LARGE：提交正文超过字节上限");
            }
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("INVALID_ARGUMENT：正文编码无效");
        }
        var json =
                tools.jackson.databind.json.JsonMapper.builder()
                        .enable(
                                tools.jackson.databind.DeserializationFeature
                                        .FAIL_ON_TRAILING_TOKENS)
                        .build();
        if (type.equals("application/json")) {
            try {
                if (json.readTree(body) == null) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("INVALID_ARGUMENT：JSON正文无效");
            }
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException invalid) {
            throw new SandboxViolationException("DENIED：HTTP地址无效");
        }
        gate.enforce(new SandboxAction(ActionType.HTTP, uri.toString(), List.of("POST")), context);
        if (Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry")) {
            throw new IllegalStateException("DENIED：不支持启用所有HTTP方法重试的运行环境");
        }
        scope.checkpoint();
        var request =
                HttpRequest.newBuilder(uri)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .header("Content-Type", type + "; charset=UTF-8")
                        .header("User-Agent", properties.userAgent())
                        .build();
        HttpResponse<byte[]> response;
        try {
            response =
                    BoundedHttpIo.exchange(client, request, scope, properties.maxResponseBytes());
        } catch (com.agentos.core.turn.TurnDeadlineExceededException
                | com.agentos.core.turn.LeaseLostException
                | ToolExecutionScope.ToolTimeoutException terminal) {
            throw terminal;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("HTTP提交未确认；提交可能已生效");
        }
        // 已收到完整状态行：服务端明确给出了结果，按状态码如实反馈，不再归为"未确认"。
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new IllegalStateException(
                    "UNSUPPORTED_REDIRECT：服务端返回跳转（HTTP " + status + "），未跟随；请求已送达原地址，提交可能已生效");
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(
                    (status >= 500 ? "HTTP_STATUS：服务端处理失败（HTTP " : "HTTP_STATUS：服务端拒绝了请求（HTTP ")
                            + status
                            + "），响应正文不回显");
        }
        try {
            String text = new String(response.body(), StandardCharsets.UTF_8);
            boolean truncated = false;
            while (true) {
                String result =
                        json.writeValueAsString(
                                java.util.Map.of(
                                        "status",
                                        response.statusCode(),
                                        "body",
                                        text,
                                        "truncated",
                                        truncated,
                                        "completion",
                                        "CONFIRMED"));
                if (result.length() <= properties.maxResultChars()) {
                    return result;
                }
                if (text.isEmpty()) {
                    throw new IllegalStateException("提交结果预算不足");
                }
                text = text.substring(0, text.length() / 2);
                truncated = true;
            }
        } catch (com.agentos.core.turn.TurnDeadlineExceededException
                | com.agentos.core.turn.LeaseLostException
                | ToolExecutionScope.ToolTimeoutException terminal) {
            throw terminal;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("HTTP提交未确认；提交可能已生效");
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
