package com.agentos.core.turn;

import com.agentos.core.session.TurnSnapshot;
import com.agentos.core.session.TurnStore;
import java.util.Objects;

/**
 * 高成本操作和消息追加前的统一检查点：先查整轮预算，再尝试续租。
 *
 * <p>预算有效只能说明“还来得及”，租约有效才说明“这一轮仍有权处理该会话”；两者缺一不可。
 * 续租通过存储端口完成，不能靠进程内锁代替数据库中的跨实例协调。
 */
public final class TurnGuard {
    private final TurnStore turnStore;

    public TurnGuard(TurnStore turnStore) {
        this.turnStore = Objects.requireNonNull(turnStore);
    }

    /** 检查失败立即抛出运行时异常，由 AgentService 持久化相应失败或超时终态。 */
    public void checkpoint(TurnSnapshot turn, TurnExecutionBudget budget) {
        if (budget.expired() || budget.hardStopped()) {
            throw new TurnDeadlineExceededException();
        }
        if (budget.cancelled() || Thread.currentThread().isInterrupted()) {
            throw new LeaseLostException();
        }
        if (!turnStore.renewLease(turn, budget)) {
            throw new LeaseLostException();
        }
    }
}
