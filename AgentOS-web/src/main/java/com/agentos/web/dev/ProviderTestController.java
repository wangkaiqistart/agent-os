package com.agentos.web.dev;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderCallException;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 手工测试 ProviderService 的临时接口，不属于正式 API，不使用 ApiResponse 信封。
 *
 * <p>只有设置了 {@code agentos.dev-test.enabled=true}（由 DevTestApplication 设置）才会注册，
 * 正式应用不会暴露它。
 *
 * <p>三个入口：
 *
 * <ul>
 *   <li>{@code GET /test/providers}：看默认模型名来自哪些环境变量；
 *   <li>{@code /test/chat}：同步调用，GET 用查询参数、POST 用 JSON；
 *   <li>{@code /test/stream}：流式调用，用 SSE 把文本增量推给浏览器或 curl。
 * </ul>
 */
@RestController
@RequestMapping("/test")
@ConditionalOnProperty(name = "agentos.dev-test.enabled", havingValue = "true")
public class ProviderTestController {

    /** SSE 默认不带字符集，部分客户端会按 Latin-1 解码，中文变乱码，所以显式写 UTF-8。 */
    private static final MediaType SSE_UTF8 =
            new MediaType("text", "event-stream", StandardCharsets.UTF_8);

    /** 流式调用的最长等待，给 SseEmitter 用；0 表示不限，这里给个上限避免连接悬挂。 */
    private static final long SSE_TIMEOUT_MS = Duration.ofMinutes(5).toMillis();

    private final ProviderService providerService;

    /** .env 是作为 Spring 属性文件加载的，不是系统环境变量，所以要经 Environment 读取。 */
    private final Environment environment;

    public ProviderTestController(ProviderService providerService, Environment environment) {
        this.providerService = providerService;
        this.environment = environment;
    }

    /** 请求体。除 message 外都可以省略：provider 缺省走默认连接，model 缺省取该连接对应的环境变量。 */
    public record ChatRequest(
            String provider,
            String model,
            String message,
            String reasoningEffort,
            Integer maxOutputTokens,
            Double temperature,
            Integer timeoutSeconds) {}

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        return Map.of(
                "primaryModel", String.valueOf(environment.getProperty("AGENTOS_LLM_MODEL")),
                "deepseekModel", String.valueOf(environment.getProperty("AGENTOS_DEEPSEEK_MODEL")),
                "usage",
                        List.of(
                                "GET  /test/chat?message=你好&provider=primary",
                                "POST /test/chat   {\"message\":\"你好\",\"provider\":\"deepseek\"}",
                                "GET  /test/stream?message=讲个笑话",
                                "可选参数：model、reasoningEffort、maxOutputTokens、temperature、timeoutSeconds"));
    }

    @GetMapping("/chat")
    public ResponseEntity<Object> chatGet(
            @RequestParam String message,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String reasoningEffort,
            @RequestParam(required = false) Integer maxOutputTokens,
            @RequestParam(required = false) Double temperature,
            @RequestParam(required = false) Integer timeoutSeconds) {
        return chat(
                new ChatRequest(
                        provider,
                        model,
                        message,
                        reasoningEffort,
                        maxOutputTokens,
                        temperature,
                        timeoutSeconds));
    }

    @PostMapping("/chat")
    public ResponseEntity<Object> chat(@RequestBody ChatRequest body) {
        try {
            ProviderResult result = providerService.call(toRequest(body));
            return ResponseEntity.ok(summarize(result));
        } catch (ProviderCallException error) {
            // 模型服务侧失败：带上每次尝试的记录，方便看重试和失败原因。
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(
                            Map.of(
                                    "error", error.getMessage(),
                                    "errorCode", error.getErrorCode().name(),
                                    "calls", error.getCalls()));
        } catch (AgentOSException error) {
            // 外呼前校验失败（未知 provider、缺模型等）。
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "error", error.getMessage(),
                                    "errorCode", error.getErrorCode().name()));
        }
    }

    @GetMapping("/stream")
    public ResponseEntity<SseEmitter> streamGet(
            @RequestParam String message,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String reasoningEffort,
            @RequestParam(required = false) Integer maxOutputTokens,
            @RequestParam(required = false) Double temperature,
            @RequestParam(required = false) Integer timeoutSeconds) {
        return stream(
                new ChatRequest(
                        provider,
                        model,
                        message,
                        reasoningEffort,
                        maxOutputTokens,
                        temperature,
                        timeoutSeconds));
    }

    /**
     * 流式调用。事件类型：{@code delta}（文本增量）、{@code done}（完整结果和调用记录）、
     * {@code error}（失败原因）。
     */
    @PostMapping("/stream")
    public ResponseEntity<SseEmitter> stream(@RequestBody ChatRequest body) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 流式调用会一直阻塞到结束，放到虚拟线程里跑，不占用请求线程。
        Thread.startVirtualThread(() -> {
            try {
                ProviderResult result =
                        providerService.stream(
                                toRequest(body), text -> send(emitter, "delta", text));
                send(emitter, "done", summarize(result));
                emitter.complete();
            } catch (ProviderCallException error) {
                send(
                        emitter,
                        "error",
                        Map.of(
                                "error", error.getMessage(),
                                "errorCode", error.getErrorCode().name(),
                                "calls", error.getCalls()));
                emitter.complete();
            } catch (AgentOSException error) {
                send(
                        emitter,
                        "error",
                        Map.of(
                                "error", error.getMessage(),
                                "errorCode", error.getErrorCode().name()));
                emitter.complete();
            }
        });
        return ResponseEntity.ok().contentType(SSE_UTF8).body(emitter);
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException clientGone) {
            // 浏览器关掉页面后再写会失败；抛出让 ProviderService 把它记成"调用方取消"。
            throw new IllegalStateException("客户端已断开", clientGone);
        }
    }

    private ProviderRequest toRequest(ChatRequest body) {
        String provider = blankToNull(body.provider());
        Profile profile =
                new Profile(
                        provider,
                        blankToNull(body.model()) != null ? body.model() : defaultModel(provider),
                        blankToNull(body.reasoningEffort()),
                        body.maxOutputTokens(),
                        body.temperature());
        Instant deadline =
                body.timeoutSeconds() == null
                        ? null
                        : Instant.now().plusSeconds(body.timeoutSeconds());
        return new ProviderRequest(
                profile, List.of(new UserMessage(body.message())), null, null, deadline, null);
    }

    /** 没写 model 时，按连接名取 .env 里对应的模型；deepseek 用它自己的，其余用 primary 的。 */
    private String defaultModel(String provider) {
        return "deepseek".equalsIgnoreCase(provider)
                ? environment.getProperty("AGENTOS_DEEPSEEK_MODEL")
                : environment.getProperty("AGENTOS_LLM_MODEL");
    }

    private static Map<String, Object> summarize(ProviderResult result) {
        var output = result.response().getResults().getFirst().getOutput();
        List<LlmCallRecord> calls = result.calls();
        return Map.of(
                "text", String.valueOf(output.getText()),
                "toolCalls", output.getToolCalls(),
                "calls", calls);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
