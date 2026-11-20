package com.agentos.core.turn;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.CallCorrelation;
import com.agentos.core.provider.ProviderCallException;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
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
import org.springframework.ai.chat.model.Generation;

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

    /**
     * redactor 参数保留以兼容既有装配：工具事件改为由 ToolExecutor 与 GovernedToolCallback 按单个工具发出，
     * 脱敏与审计在同一处完成，循环本身不再摘要工具参数和结果。
     */
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
        TurnEventPublisher events = publisher(sink, budget, turn);
        MDC.put("sessionId", turn.sessionId().toString());
        MDC.put("turnId", turn.turnId().toString());
        MDC.put("agent", agent.profile().name());
        try {
            int maxIterations = agent.profile().settings().maxIterations();
            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                MDC.put("iteration", Integer.toString(iteration));
                guard.checkpoint(turn, budget);
                events.publish(new TurnEventPayload.StepStarted(iteration));
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
                var message = new MessageTracker(events, iteration);
                ProviderResult called;
                try {
                    called =
                            sink.streaming()
                                    ? provider.stream(request, message::delta)
                                    : provider.call(request);
                } catch (ProviderCallException failure) {
                    message.end();
                    events.publish(failedStep(iteration, agent, failure));
                    throw failure;
                }
                var response = called.response();
                var generation = response.getResult();
                if (!sink.streaming() && generation != null) {
                    // 同步调用拿到全文后一次性发出开始、全文增量、结束三条消息事件。
                    message.delta(generation.getOutput().getText());
                }
                message.end();
                events.publish(finishedStep(iteration, agent, called, generation));
                guard.checkpoint(turn, budget);
                if (generation == null) {
                    return new Result(
                            TurnStatus.FAILED,
                            "模型返回了空回答",
                            iteration,
                            TurnFailureCode.EMPTY_RESPONSE);
                }
                var output = generation.getOutput();
                String answer = output.getText();
                // 截断或被过滤的响应不完整：不执行其中的工具、不写助手消息，以对应错误码结束本轮。
                Result incomplete = incompleteResult(generation, iteration);
                if (incomplete != null) {
                    log.warn(
                            "第 {} 次模型调用未正常结束 failureCode={}，本轮停止",
                            iteration,
                            incomplete.failureCode());
                    return incomplete;
                }
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
                        return new Result(
                                TurnStatus.FAILED,
                                "模型返回了空回答",
                                iteration,
                                TurnFailureCode.EMPTY_RESPONSE);
                    }
                    messages.append(turn, iteration, List.of(new AssistantMessage(text)));
                    return new Result(TurnStatus.SUCCEEDED, text, iteration);
                }
                ToolCallLedger.validate(output.getToolCalls());
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
                                capabilities,
                                events);
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
                    return new Result(
                            TurnStatus.MAX_ITERATIONS,
                            limit,
                            iteration,
                            TurnFailureCode.MAX_ITERATIONS);
                }
            }
            String limit = "已达到模型调用上限（" + maxIterations + "），本轮停止继续调用模型";
            return new Result(
                    TurnStatus.MAX_ITERATIONS,
                    limit,
                    maxIterations,
                    TurnFailureCode.MAX_ITERATIONS);
        } finally {
            MDC.clear();
        }
    }

    /**
     * DefaultAgentService 传入的输出端本身就是发布器；直接调用循环（测试、调试）时临时包一层，
     * 序号规则相同，只是没有轮次开始和终态事件。
     */
    private static TurnEventPublisher publisher(
            TurnSink sink, TurnExecutionBudget budget, TurnSnapshot turn) {
        if (sink instanceof TurnEventPublisher publisher) {
            return publisher;
        }
        var managed = new ManagedTurnSink(sink, budget, turn.turnId());
        managed.bindSession(turn.sessionId());
        return managed;
    }

    /** 步骤结束事件：模型、结束原因和用量取自本次逻辑调用的最后一条实际请求记录。 */
    private static TurnEventPayload.StepFinished finishedStep(
            int iteration, LoadedAgent agent, ProviderResult called, Generation generation) {
        String fallbackReason =
                generation == null || generation.getMetadata() == null
                        ? null
                        : generation.getMetadata().getFinishReason();
        if (called.calls().isEmpty()) {
            return new TurnEventPayload.StepFinished(
                    iteration,
                    TurnEventPayload.StepOutcome.SUCCEEDED,
                    fallbackReason,
                    agent.profile().providerProfile().model(),
                    null,
                    null,
                    null,
                    null);
        }
        var last = called.calls().getLast();
        return new TurnEventPayload.StepFinished(
                iteration,
                TurnEventPayload.StepOutcome.SUCCEEDED,
                last.finishReason() == null ? fallbackReason : last.finishReason(),
                last.requestedModel(),
                last.responseModel(),
                last.promptTokens(),
                last.completionTokens(),
                null);
    }

    private static TurnEventPayload.StepFinished failedStep(
            int iteration, LoadedAgent agent, ProviderCallException failure) {
        var last = failure.getCalls().isEmpty() ? null : failure.getCalls().getLast();
        return new TurnEventPayload.StepFinished(
                iteration,
                TurnEventPayload.StepOutcome.FAILED,
                null,
                last == null ? agent.profile().providerProfile().model() : last.requestedModel(),
                null,
                null,
                null,
                last == null ? null : last.failureKind());
    }

    /**
     * 一次模型调用内的助手消息：收到第一段非空文字时发开始事件，之后每段发增量，模型返回后发结束事件。
     * 空片段不发事件；只有工具调用、没有文字时不产生消息事件。
     */
    private static final class MessageTracker {
        private final TurnEventPublisher events;
        private final int iteration;
        // 流式时 delta 在模型流的回调线程上调用，end 在循环线程上调用。
        private volatile String messageId;
        private volatile boolean ended;

        MessageTracker(TurnEventPublisher events, int iteration) {
            this.events = events;
            this.iteration = iteration;
        }

        void delta(String text) {
            if (text == null || text.isEmpty() || ended) {
                return;
            }
            if (messageId == null) {
                messageId = java.util.UUID.randomUUID().toString();
                events.publish(new TurnEventPayload.MessageStarted(messageId, iteration, null));
            }
            events.publish(new TurnEventPayload.MessageDelta(messageId, text));
        }

        void end() {
            if (messageId != null && !ended) {
                ended = true;
                events.publish(new TurnEventPayload.MessageEnded(messageId));
            }
        }
    }

    /** 截断时提示用户的固定文案；同时写进 error_message（前缀为错误码）。 */
    static final String TRUNCATED_TEXT = "模型输出达到上限被截断，可调大 max_output_tokens 或拆分任务";

    static final String FILTERED_TEXT = "回答被内容安全策略过滤";

    /**
     * 结束原因为 length 或 content_filter 时返回失败结果，否则返回 null。结束原因的大小写因服务而异，
     * 忽略大小写比较；同步与流式调用都保留了这个字段。
     */
    private static Result incompleteResult(Generation generation, int iteration) {
        String reason =
                generation.getMetadata() == null
                        ? null
                        : generation.getMetadata().getFinishReason();
        if ("length".equalsIgnoreCase(reason)) {
            return new Result(
                    TurnStatus.FAILED, TRUNCATED_TEXT, iteration, TurnFailureCode.OUTPUT_TRUNCATED);
        }
        if ("content_filter".equalsIgnoreCase(reason)) {
            return new Result(
                    TurnStatus.FAILED, FILTERED_TEXT, iteration, TurnFailureCode.CONTENT_FILTERED);
        }
        return null;
    }

    /**
     * 循环结果。
     *
     * @param failureCode 非成功时的错误码；成功或由 AgentService 根据异常决定时为 null
     */
    public record Result(
            TurnStatus status, String text, int iterations, TurnFailureCode failureCode) {
        public Result(TurnStatus status, String text, int iterations) {
            this(status, text, iterations, null);
        }
    }
}
