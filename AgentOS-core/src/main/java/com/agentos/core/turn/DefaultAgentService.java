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

/** AgentService 的默认编排实现，所有入口都汇聚到 process(request,sink)。 */
public final class DefaultAgentService implements AgentService {
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentService.class);
    private final AgentLoader loader;
    private final ContextLoader contextLoader;
    private final ProviderCatalog providers;
    private final SessionManager sessions;
    private final TurnStore turns;
    private final ReActLoop loop;
    private final TurnBudgetRegistry budgets;
    private final TurnRunner runner;

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
        return process(request, TurnSink.NOOP);
    }

    @Override
    public TurnResult process(ProcessRequest request, TurnSink sink) {
        TurnSink.require(sink);
        if (request.message() == null || request.message().isBlank()) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "消息不能为空");
        }
        LoadedAgent agent = contextLoader.load(loader.load(request.agentName()));
        providers.validate(agent.profile().providerProfile());
        UUID turnId = UUID.randomUUID();
        TurnExecutionBudget budget =
                TurnExecutionBudget.start(agent.profile().settings().turnTimeout());
        TurnSnapshot turn =
                turns.startTurn(
                        turnId,
                        new SessionKey(request.channel(), request.userId(), agent.profile().name()),
                        request.trigger(),
                        request.message(),
                        budget);
        budgets.register(turnId, budget);
        MDC.put("sessionId", turn.sessionId().toString());
        MDC.put("turnId", turn.turnId().toString());
        MDC.put("agent", agent.profile().name());
        TurnResult result;
        try {
            ReActLoop.Result outcome =
                    runner.run(
                            () -> loop.run(turn, agent, request.message(), budget, sink), budget);
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
            budgets.remove(turnId);
            MDC.remove("sessionId");
            MDC.remove("turnId");
            MDC.remove("agent");
            MDC.remove("iteration");
        }
        try {
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

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null
                ? "内部错误"
                : error.getMessage()
                        .replaceAll(
                                "(?i)(key|token|secret|password)\\s*[=:]\\s*[^\\s]+",
                                "$1=[REDACTED]");
    }
}
