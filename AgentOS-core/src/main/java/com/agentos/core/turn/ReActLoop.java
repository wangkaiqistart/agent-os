package com.agentos.core.turn;

import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.CallCorrelation;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.TurnSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/**
 * 当前 US1 的最小执行内核：拼 Prompt、检查预算与租约、调用一次模型、保存助手消息。
 *
 * <p>由 CoreAutoConfiguration 创建并传给 AgentService，本类自身没有单独注册为 Bean。
 * 这里只依赖 ProviderService、MessageStore 等端口，不知道模型服务地址或 JPA 细节。
 *
 * <p>名称保留 ReActLoop，是后续多次“模型思考 → 工具动作 → 观察结果”的扩展位置；
 * 当前没有工具执行与多次迭代，返回工具调用时明确失败，不能把它理解为完整 ReAct 已实现。
 */
public final class ReActLoop {
    private final ProviderService provider;
    private final MessageStore messages;
    private final PromptBuilder promptBuilder;
    private final TurnGuard guard;

    public ReActLoop(
            ProviderService provider,
            MessageStore messages,
            PromptBuilder promptBuilder,
            TurnGuard guard) {
        this.provider = Objects.requireNonNull(provider);
        this.messages = Objects.requireNonNull(messages);
        this.promptBuilder = Objects.requireNonNull(promptBuilder);
        this.guard = Objects.requireNonNull(guard);
    }

    /** 运行一个轮次；成功时助手消息已追加，但轮次终态和租约仍由 AgentService 收尾。 */
    public Result run(
            TurnSnapshot turn,
            LoadedAgent agent,
            String userMessage,
            TurnExecutionBudget budget,
            TurnSink sink) {
        // 当前传空历史，数据库已有的前轮消息不会自动进入本次模型上下文。
        List<Message> prompt = promptBuilder.build(agent, List.of(), userMessage);
        // 在外呼前确认还能继续执行；续租本身是短事务，不包住下面的 HTTP 等待。
        guard.checkpoint(turn, budget);
        var request =
                new ProviderRequest(
                        agent.profile().providerProfile(),
                        prompt,
                        List.of(), // 当前不向模型暴露工具定义。
                        Map.of("turnContext", turn.turnId().toString()),
                        budget.deadline(),
                        new CallCorrelation(turn.sessionId(), turn.turnId(), 1));
        // 同步与流式共用请求、审计关联和预算；流式只增加 onDelta 过程输出。
        ProviderResult result =
                sink.streaming() ? provider.stream(request, sink::onDelta) : provider.call(request);
        // 模型已返回也不代表可以写答案：等待期间可能超时或失去租约，需要再次检查。
        guard.checkpoint(turn, budget);
        var generation = result.response().getResults().getFirst();
        String text = generation.getOutput().getText();
        if (!generation.getOutput().getToolCalls().isEmpty()) {
            return new Result(TurnStatus.FAILED, "当前版本暂不支持工具调用", 1);
        }
        if (text == null || text.isBlank()) {
            return new Result(TurnStatus.FAILED, "模型返回了空回答", 1);
        }
        // USER 已在 startTurn 写入；这里只补 ASSISTANT。iteration=1 是本轮第一次模型调用。
        messages.append(turn, 1, List.of(new AssistantMessage(text)));
        return new Result(TurnStatus.SUCCEEDED, text, 1);
    }

    /** 循环层结果，不含持久化确认；AgentService 收尾后才形成对外的 TurnResult。 */
    public record Result(TurnStatus status, String text, int iterations) {}
}
