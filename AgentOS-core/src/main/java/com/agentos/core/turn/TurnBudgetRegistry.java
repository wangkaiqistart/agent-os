package com.agentos.core.turn;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前进程内的在途轮次预算表，以 turnId 显式查找，不用 ThreadLocal 保存业务身份。
 *
 * <p>ConcurrentHashMap 支持不同轮次并发登记；它不是数据库租约，也不能查询其他实例的预算。
 * AgentService 在开始执行后登记，并在 finally 移除，避免已结束轮次长期占用内存。
 */
public final class TurnBudgetRegistry {
    private final ConcurrentHashMap<UUID, TurnExecutionBudget> budgets = new ConcurrentHashMap<>();

    public void register(UUID turnId, TurnExecutionBudget budget) {
        budgets.put(turnId, budget);
    }

    /** 未登记或已经移除时返回 null；调用者不能把它解释成无限预算。 */
    public TurnExecutionBudget get(UUID turnId) {
        return budgets.get(turnId);
    }

    public void remove(UUID turnId) {
        budgets.remove(turnId);
    }
}
