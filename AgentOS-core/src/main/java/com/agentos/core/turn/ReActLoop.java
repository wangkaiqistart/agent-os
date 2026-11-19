package com.agentos.core.turn;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.CallCorrelation;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.session.HistoryTurn;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionSnapshot;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolCatalog;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 模型思考 → 官方 Manager 执行工具 → 完整结果回填的单轮循环。
 * 只依赖 core 端口，模型、工具实现和数据库均由装配层注入；跨用户消息按完整轮次恢复。
 */
public final class ReActLoop {
    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);
    private final ProviderService provider;
    private final MessageStore messages;
    private final PromptBuilder promptBuilder;
    private final TurnGuard guard;
    private final ToolCatalog catalog;
    private final ToolExecutor executor;
    private final Redactor redactor;

    public ReActLoop(
            ProviderService provider,
            MessageStore messages,
            PromptBuilder prompts,
            TurnGuard guard) {
        this(
                provider,
                messages,
                prompts,
                guard,
                new ToolCatalog() {
                    public void validate(AgentProfile p) {
                        if (!p.tools().isEmpty()) {
                            throw new IllegalArgumentException("工具目录未装配");
                        }
                    }

                    public List<org.springframework.ai.tool.ToolCallback> callbacks(
                            AgentProfile p) {
                        return List.of();
                    }
                },
                null,
                new Redactor(List::of));
    }

    public ReActLoop(
            ProviderService provider,
            MessageStore messages,
            PromptBuilder prompts,
            TurnGuard guard,
            ToolCatalog catalog,
            ToolExecutor executor) {
        this(provider, messages, prompts, guard, catalog, executor, new Redactor(List::of));
    }

    public ReActLoop(
            ProviderService provider,
            MessageStore messages,
            PromptBuilder prompts,
            TurnGuard guard,
            ToolCatalog catalog,
            ToolExecutor executor,
            Redactor redactor) {
        this.provider = Objects.requireNonNull(provider);
        this.messages = Objects.requireNonNull(messages);
        this.promptBuilder = Objects.requireNonNull(prompts);
        this.guard = Objects.requireNonNull(guard);
        this.catalog = Objects.requireNonNull(catalog);
        this.executor = executor;
        this.redactor = redactor;
    }

    /** 在 startTurn 之前校验名单，未知工具不会占用会话，更不会触发模型请求。 */
    public void validate(AgentProfile profile) {
        catalog.validate(profile);
    }

    public com.agentos.core.tool.ToolCatalogSnapshot resolve(AgentProfile profile) {
        return catalog.resolve(profile);
    }

    public Result run(
            TurnSnapshot turn,
            LoadedAgent agent,
            String userMessage,
            TurnExecutionBudget budget,
            TurnSink sink) {
        return run(turn, agent, userMessage, budget, sink, turn.sessionKey().channel());
    }

    /** 入口触发来源显式传递，不从 MDC 或线程状态推断。 */
    public Result run(
            TurnSnapshot turn,
            LoadedAgent agent,
            String userMessage,
            TurnExecutionBudget budget,
            TurnSink sink,
            String trigger) {
        return run(turn, agent, userMessage, budget, sink, trigger, resolve(agent.profile()));
    }

    /** 服务在建轮次前捕获的同一个快照，整个循环只使用这些绑定。 */
    public Result run(
            TurnSnapshot turn,
            LoadedAgent agent,
            String userMessage,
            TurnExecutionBudget budget,
            TurnSink sink,
            String trigger,
            com.agentos.core.tool.ToolCatalogSnapshot snapshot) {
        return run(
                turn,
                agent,
                userMessage,
                budget,
                sink,
                trigger,
                snapshot,
                com.agentos.core.interaction.TurnRuntimeCapabilities.NONE);
    }

    public Result run(
            TurnSnapshot turn,
            LoadedAgent agent,
            String userMessage,
            TurnExecutionBudget budget,
            TurnSink sink,
            String trigger,
            com.agentos.core.tool.ToolCatalogSnapshot snapshot,
            com.agentos.core.interaction.TurnRuntimeCapabilities capabilities) {
        // startTurn 已经把当前用户消息写入存储；读取历史时明确排除 RUNNING 轮次，避免当前消息重复。
        SessionSnapshot session =
                new SessionSnapshot(
                        turn.sessionId(), turn.sessionKey(), "active", turn.owner(), null);
        List<HistoryTurn> previousTurns =
                agent.profile().settings().maxHistoryTurns() == 0
                                || agent.profile().settings().maxHistoryTokens() == 0
                        ? List.of()
                        : messages.recentTurns(
                                session, agent.profile().settings().maxHistoryTurns());
        List<Message> history = promptBuilder.build(agent, previousTurns, userMessage);
        var callbacks = snapshot.modelCallbacks();
        MDC.put("sessionId", turn.sessionId().toString());
        MDC.put("turnId", turn.turnId().toString());
        MDC.put("agent", agent.profile().name());
        try {
            int maxIterations = agent.profile().settings().maxIterations();
            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                MDC.put("iteration", Integer.toString(iteration));
                guard.checkpoint(turn, budget);
                log.info(
                        "第 {}/{} 次模型调用开始 model={} historyMessages={} tools={} remainingSec={}",
                        iteration,
                        maxIterations,
                        agent.profile().providerProfile().model(),
                        history.size(),
                        callbacks.size(),
                        budget.remaining().toSeconds());
                var context =
                        new TurnContext(
                                turn.sessionId(),
                                turn.turnId(),
                                agent,
                                trigger,
                                iteration,
                                budget.deadline());
                var request =
                        new ProviderRequest(
                                agent.profile().providerProfile(),
                                history,
                                callbacks,
                                Map.of(ToolContextKeys.TURN_CONTEXT, context),
                                budget.deadline(),
                                new CallCorrelation(turn.sessionId(), turn.turnId(), iteration));
                var response =
                        (sink.streaming()
                                        ? provider.stream(request, sink::onDelta)
                                        : provider.call(request))
                                .response();
                guard.checkpoint(turn, budget);
                var generation = response.getResult();
                if (generation == null) {
                    return new Result(TurnStatus.FAILED, "模型返回了空回答", iteration);
                }
                var output = generation.getOutput();
                String answer = output.getText();
                log.info(
                        "第 {} 次模型调用返回 toolCalls={} {}",
                        iteration,
                        output.getToolCalls().size(),
                        output.getToolCalls().isEmpty()
                                ? "answerChars=" + (answer == null ? 0 : answer.length())
                                : "tools="
                                        + output.getToolCalls().stream()
                                                .map(call -> call.name())
                                                .toList());
                if (output.getToolCalls().isEmpty()) {
                    String text = answer;
                    if (text == null || text.isBlank()) {
                        return new Result(TurnStatus.FAILED, "模型返回了空回答", iteration);
                    }
                    messages.append(turn, iteration, List.of(new AssistantMessage(text)));
                    return new Result(TurnStatus.SUCCEEDED, text, iteration);
                }
                ToolCallLedger.validate(output.getToolCalls());
                for (var call : output.getToolCalls()) {
                    guard.checkpoint(turn, budget);
                    sink.onToolCall(
                            call.id(), call.name(), redactor.summarize(call.arguments(), 10000));
                }
                if (executor == null) {
                    throw new IllegalStateException("工具执行器未装配");
                }
                // 不捕获致命异常；Manager 未正常返回时，这一批消息绝不落库。
                var executed =
                        executor.execute(
                                history,
                                response,
                                snapshot,
                                context,
                                budget,
                                () -> guard.checkpoint(turn, budget),
                                capabilities);
                for (var added : executed.added()) {
                    if (added instanceof ToolResponseMessage toolMessage) {
                        for (var result : toolMessage.getResponses()) {
                            guard.checkpoint(turn, budget);
                            sink.onToolResult(
                                    result.id(),
                                    result.name(),
                                    redactor.redact(result.responseData()));
                        }
                    }
                }
                guard.checkpoint(turn, budget);
                messages.append(turn, iteration, executed.added());
                history = executed.history();
                if (executed.returnDirect()) {
                    String text =
                            executed.added().stream()
                                    .filter(ToolResponseMessage.class::isInstance)
                                    .map(ToolResponseMessage.class::cast)
                                    .flatMap(m -> m.getResponses().stream())
                                    .map(ToolResponseMessage.ToolResponse::responseData)
                                    .collect(Collectors.joining("\n"));
                    return new Result(TurnStatus.SUCCEEDED, text, iteration);
                }
                if (iteration == maxIterations) {
                    log.warn("已达到模型调用上限 maxIterations={}，本轮停止继续调用模型", maxIterations);
                    String limit = "已达到模型调用上限（" + maxIterations + "），本轮停止继续调用模型";
                    messages.append(turn, iteration, List.of(new AssistantMessage(limit)));
                    return new Result(TurnStatus.MAX_ITERATIONS, limit, iteration);
                }
            }
            String limit = "已达到模型调用上限（" + maxIterations + "），本轮停止继续调用模型";
            return new Result(TurnStatus.MAX_ITERATIONS, limit, maxIterations);
        } finally {
            MDC.clear();
        }
    }

    public record Result(TurnStatus status, String text, int iterations) {}
}
