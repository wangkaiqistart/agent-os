package com.agentos.web.dev;

import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnSink;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 临时调试完整 AgentService/ReActLoop 链路的 HTTP 入口。
 *
 * <p>默认关闭，只在有效配置设置
 * {@code agentos.debug.react-loop.enabled=true} 时注册（临时调试主类已设置此默认值）。调试完成后可以直接删除本类，
 * 不属于正式 API，也不使用 Web 模块的 ApiResponse 信封。
 */
@RestController
@RequestMapping("/debug/react-loop")
@ConditionalOnProperty(name = "agentos.debug.react-loop.enabled", havingValue = "true")
public final class AgentLoopDebugController {
    private static final MediaType SSE_UTF8 =
            new MediaType("text", "event-stream", StandardCharsets.UTF_8);
    // 这里只限制 Servlet 异步响应等待，和 Agent 的业务预算、Provider 请求超时不是同一层。
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final AgentService agentService;

    public AgentLoopDebugController(AgentService agentService) {
        this.agentService = Objects.requireNonNull(agentService, "agentService");
    }

    /** 查看当前调试接口的请求格式。 */
    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "enabled", true,
                "sync", "POST /debug/react-loop",
                "stream", "POST /debug/react-loop/stream",
                "body", Map.of("agent", "answer", "message", "你好", "userId", "debug-user"),
                "warning", "仅用于本地调试；会真实创建 session、turn、消息和 llm_call");
    }

    /**
     * 在请求线程等待完整业务结果；HTTP 200 只代表调试响应成功，业务是否成功要看 result.status。
     * 非 streaming 的 sink 通常只收到终态事件，前置参数/配置异常则直接交给 Web 异常处理。
     */
    @PostMapping
    public DebugResponse process(@RequestBody DebugRequest request) {
        // 每个请求独享事件列表，不放在 Controller 单例字段中，防止并发请求互相串数据。
        List<TraceEvent> events = new ArrayList<>();
        TurnResult result =
                agentService.process(request.toProcessRequest(), new TraceSink(events::add));
        return new DebugResponse(result, List.copyOf(events));
    }

    /**
     * 先返回 SSE 连接，再由后台线程调用同一个 AgentService，投递 delta 与终态。
     * 此处没有实现正式 API 的 HTTP 等待超时返回 202，也没有绑定断连取消业务的机制。
     */
    @PostMapping("/stream")
    public ResponseEntity<SseEmitter> stream(@RequestBody DebugRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 这是输出适配层的虚拟线程，使 Servlet 请求线程可以返回；
        // AgentService 内的 TurnRunner 还会另起执行线程，两处断点可能位于不同线程。
        Thread.startVirtualThread(() -> {
            try {
                agentService.process(
                        request.toProcessRequest(),
                        new TraceSink(event -> send(emitter, event)) {
                            @Override
                            public boolean streaming() {
                                // 告诉 ReActLoop 选择 Provider.stream，而不是只在同步调用外面套 SSE。
                                return true;
                            }
                        });
                emitter.complete();
            } catch (RuntimeException error) {
                // 例如申请轮次前的 Agent/Provider 校验异常，尚无 TurnResult，单独发 exception 事件。
                send(emitter, new TraceEvent("exception", safeMessage(error), null));
                emitter.complete();
            }
        });
        return ResponseEntity.ok().contentType(SSE_UTF8).body(emitter);
    }

    private static void send(SseEmitter emitter, TraceEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (Exception clientGone) {
            // 转为回调运行时异常向上传递；这不保证客户端断开就能取消已发生的模型请求。
            throw new IllegalStateException("调试客户端已断开", clientGone);
        }
    }

    /** 仅遮蔽常见 key=value 凭证片段；不是覆盖所有格式的通用脱敏器。 */
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null
                ? error.getClass().getSimpleName()
                : message.replaceAll(
                        "(?i)(key|token|secret|password)\\s*[=:]\\s*[^\\s]+", "$1=[REDACTED]");
    }

    /**
     * 调试 JSON 请求，由 Spring MVC 转为 record。
     *
     * @param agent agents 下的目录名称，例如 answer；不是工作区根路径
     * @param message 用户消息，不能为空的业务校验由 AgentService 负责
     * @param userId 会话区分标识，缺省 debug-user；不是已验证身份
     * @param trigger 触发来源摘要，缺省 debug-http；不改变固定的 debug-http 会话渠道
     */
    public record DebugRequest(String agent, String message, String userId, String trigger) {
        ProcessRequest toProcessRequest() {
            String actualUser = userId == null || userId.isBlank() ? "debug-user" : userId;
            String actualTrigger = trigger == null || trigger.isBlank() ? "debug-http" : trigger;
            return new ProcessRequest("debug-http", actualUser, agent, message, actualTrigger);
        }
    }

    /** 业务结果与本次观察到的回调事件；持久化确认在 result.persistenceStatus 中单独表达。 */
    public record DebugResponse(TurnResult result, List<TraceEvent> events) {}

    /** delta 使用 text；complete/error 使用 result；前置 exception 使用 text。 */
    public record TraceEvent(String type, String text, TurnResult result) {}

    /** 把 core 回调适配成收集列表或发送 SSE 的 Consumer，业务层无需知道 Servlet 类型。 */
    private static class TraceSink implements TurnSink {
        private final Consumer<TraceEvent> output;

        private TraceSink(Consumer<TraceEvent> output) {
            this.output = output;
        }

        @Override
        public void onDelta(String text) {
            output.accept(new TraceEvent("delta", text, null));
        }

        @Override
        public void onComplete(TurnResult result) {
            // AgentService 完成存储收尾后才调用此处；确认是否成功仍需检查 persistenceStatus 字段。
            output.accept(new TraceEvent("complete", null, result));
        }

        @Override
        public void onError(TurnResult result) {
            output.accept(new TraceEvent("error", null, result));
        }
    }
}
