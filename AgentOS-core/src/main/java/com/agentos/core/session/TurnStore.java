package com.agentos.core.session;

import com.agentos.core.turn.TurnExecutionBudget;
import java.util.Optional;
import java.util.UUID;

/** 轮次租约与状态端口。实现必须用短事务完成每次操作。 */
public interface TurnStore {
    /** 原子取得会话租约并创建 RUNNING 轮次和首条 USER 消息；忙会话应拒绝并发启动。 */
    TurnSnapshot startTurn(
            UUID turnId,
            SessionKey key,
            String trigger,
            String userMessage,
            TurnExecutionBudget budget);

    /** 仅在租约尚有效且仍归当前 owner 时续租；返回 false 后执行层应立即停止。 */
    boolean renewLease(TurnSnapshot turn, TurnExecutionBudget budget);

    /** 保存终态并释放仍属于本轮的租约；不能释放其他轮次后来取得的租约。 */
    FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String errorMessage);

    /** 正常循环收口时同时保存实际迭代数；旧端口实现可继续沿用基础收尾行为。 */
    default FinishResult finishAndRelease(
            TurnSnapshot turn, String status, String text, String errorMessage, int iterations) {
        return finishAndRelease(turn, status, text, errorMessage);
    }

    /** 查询指定轮次的快照，不隐式创建记录。 */
    Optional<TurnSnapshot> find(UUID turnId);

    /** 将失去有效会话租约的 RUNNING 记录改为 INTERRUPTED；当前尚未接定时触发器。 */
    int reapInterrupted();

    /** 业务状态与存储确认分开返回；false 表示不能向输出端宣称收尾已经可靠完成。 */
    record FinishResult(
            String status, boolean persistenceConfirmed, String text, String errorMessage) {}
}
