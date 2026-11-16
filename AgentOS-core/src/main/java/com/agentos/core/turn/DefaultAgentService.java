package com.agentos.core.turn;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.session.SessionKey;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 一轮对话的总协调者：加载 Agent、申请会话租约、运行循环、持久化终态，最后通知输出端。
 *
 * <p>由 CoreAutoConfiguration 注册为 AgentService Bean。CLI 和临时 HTTP 入口共用本类，
 * 因而这里不处理终端、Servlet 或 SSE 细节；这些差异通过 ProcessRequest 和 TurnSink 适配。
 *
 * <p>本类不加整方法事务：模型调用可能等待很久，事务只在注入的存储端口内短暂开启。
 * 当前循环只有一次无工具模型调用，历史恢复、工具执行和新会话语义尚未接入。
 */
public final class DefaultAgentService implements AgentService {
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentService.class);
    private final AgentLoader loader;
    private final ContextLoader contextLoader;
    private final ProviderCatalog providers;
    // 会话管理依赖已预留；当前 process 的会话创建直接由 TurnStore 完成，尚未使用它归档旧会话。
    private final SessionManager sessions;
    private final TurnStore turns;
    private final ReActLoop loop;
    private final TurnBudgetRegistry budgets;
    private final TurnRunner runner;

    /** 默认使用虚拟线程执行器；测试也可以通过另一个构造器显式传入执行器。 */
    public DefaultAgentService(
            AgentLoader loader,
            ContextLoader contextLoader,
            ProviderCatalog providers,
            SessionManager sessions,
            TurnStore turns,
            ReActLoop loop,
            TurnBudgetRegistry budgets) {
        this(loader, contextLoader, providers, sessions, turns, loop, budgets, new TurnRunner());
    }

    public DefaultAgentService(
            AgentLoader loader,
            ContextLoader contextLoader,
            ProviderCatalog providers,
            SessionManager sessions,
            TurnStore turns,
            ReActLoop loop,
            TurnBudgetRegistry budgets,
            TurnRunner runner) {
        this.loader = Objects.requireNonNull(loader);
        this.contextLoader = Objects.requireNonNull(contextLoader);
        this.providers = Objects.requireNonNull(providers);
        this.sessions = Objects.requireNonNull(sessions);
        this.turns = Objects.requireNonNull(turns);
        this.loop = Objects.requireNonNull(loop);
        this.budgets = Objects.requireNonNull(budgets);
        this.runner = Objects.requireNonNull(runner);
    }

    @Override
    public TurnResult process(ProcessRequest request) {
        // 无过程输出的调用也走同一条链路，避免同步与流式各实现一套业务规则。
        return process(request, TurnSink.NOOP);
    }

    /**
     * 阻塞等待本轮结果；streaming 只决定是否转发模型增量，并不使本方法提前返回。
     *
     * <p>申请轮次前的参数、Agent 和 Provider 错误直接抛给入口；成功申请后，
     * 循环运行异常在下面转换为业务终态。存储收尾本身失败仍可能向外抛出异常。
     */
    @Override
    public TurnResult process(ProcessRequest request, TurnSink sink) {
        TurnSink.require(sink);
        if (request.message() == null || request.message().isBlank()) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "消息不能为空");
        }
        // 1. 先检查文件和连接配置，再申请租约，避免明显无效的请求占用会话。
        // PromptBuilder 组装消息时还会读取一次 Bootstrap，所以缺文件时可能看到两组警告。
        LoadedAgent agent = contextLoader.load(loader.load(request.agentName()));
        providers.validate(agent.profile().providerProfile());
        UUID turnId = UUID.randomUUID();
        TurnExecutionBudget budget =
                TurnExecutionBudget.start(agent.profile().settings().turnTimeout());
        // 2. 一个短事务内确保 session 存在、取得租约、写 turn 和 USER 消息。
        // channel + userId + Agent 名称区分活跃会话；同会话已有有效租约时这里会拒绝请求。
        TurnSnapshot turn =
                turns.startTurn(
                        turnId,
                        new SessionKey(request.channel(), request.userId(), agent.profile().name()),
                        request.trigger(),
                        request.message(),
                        budget);
        // 预算按 turnId 显式登记；MDC 只用于当前线程的日志关联，不作为业务上下文传递。
        budgets.register(turnId, budget);
        MDC.put("sessionId", turn.sessionId().toString());
        MDC.put("turnId", turn.turnId().toString());
        MDC.put("agent", agent.profile().name());
        TurnResult result;
        try {
            // 3. TurnRunner 在另一条虚拟线程中跑循环，并对外层等待设置 hard stop。
            // budget、turn、agent 都通过参数传过去，当前线程的事务与 MDC 不会自动跟过去。
            ReActLoop.Result outcome =
                    runner.run(
                            () -> loop.run(turn, agent, request.message(), budget, sink), budget);
            // 4. 先完成持久化收尾，再生成给用户的结果。业务状态和持久化确认分开表达。
            TurnStore.FinishResult finish =
                    turns.finishAndRelease(
                            turn,
                            outcome.status().name(),
                            outcome.text(),
                            outcome.status() == TurnStatus.SUCCEEDED ? null : outcome.text());
            result =
                    new TurnResult(
                            turnId,
                            outcome.status(),
                            outcome.text(),
                            outcome.iterations(),
                            finish.persistenceConfirmed()
                                    ? PersistenceStatus.CONFIRMED
                                    : PersistenceStatus.UNCONFIRMED);
        } catch (TurnDeadlineExceededException e) {
            // 超时同样需要更新轮次、释放属于自己的租约，不能只取消线程就直接返回。
            TurnStore.FinishResult finish =
                    turns.finishAndRelease(
                            turn, TurnStatus.TIMED_OUT.name(), "本轮处理超时", e.getMessage());
            result =
                    new TurnResult(
                            turnId,
                            TurnStatus.TIMED_OUT,
                            "本轮处理超时",
                            0,
                            finish.persistenceConfirmed()
                                    ? PersistenceStatus.CONFIRMED
                                    : PersistenceStatus.UNCONFIRMED);
        } catch (RuntimeException e) {
            // 给用户固定失败文案；存储摘要做有限脱敏，不向输出端返回原始内部异常。
            TurnStore.FinishResult finish =
                    turns.finishAndRelease(turn, TurnStatus.FAILED.name(), "处理失败", safeMessage(e));
            result =
                    new TurnResult(
                            turnId,
                            TurnStatus.FAILED,
                            "处理失败",
                            0,
                            finish.persistenceConfirmed()
                                    ? PersistenceStatus.CONFIRMED
                                    : PersistenceStatus.UNCONFIRMED);
        } finally {
            // 无论循环成功、失败或收尾抛错，都解除进程内预算引用和当前线程的日志标记。
            budgets.remove(turnId);
            MDC.remove("sessionId");
            MDC.remove("turnId");
            MDC.remove("agent");
            MDC.remove("iteration");
        }
        try {
            // 5. 回调是结果投递，不是事务的一部分。客户端断开不会撤销已经确认的业务结果。
            if (result.status() == TurnStatus.SUCCEEDED) {
                sink.onComplete(result);
            } else {
                sink.onError(result);
            }
        } catch (RuntimeException callbackError) {
            log.warn("终结通知失败：{}", callbackError.getClass().getSimpleName());
        }
        return result;
    }

    /** 仅遮蔽常见 key=value 形式；调用方仍不应把含凭证的原始响应塞入异常消息。 */
    private static String safeMessage(Throwable error) {
        return error.getMessage() == null
                ? "内部错误"
                : error.getMessage()
                        .replaceAll(
                                "(?i)(key|token|secret|password)\\s*[=:]\\s*[^\\s]+",
                                "$1=[REDACTED]");
    }
}
