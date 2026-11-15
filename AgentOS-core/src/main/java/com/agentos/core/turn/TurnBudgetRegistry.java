package com.agentos.core.turn;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 显式按 turnId 传递预算，禁止用 ThreadLocal。 */
public final class TurnBudgetRegistry {
    private final ConcurrentHashMap<UUID, TurnExecutionBudget> budgets = new ConcurrentHashMap<>();

    public void register(UUID turnId, TurnExecutionBudget budget) {
        budgets.put(turnId, budget);
    }

    public TurnExecutionBudget get(UUID turnId) {
        return budgets.get(turnId);
    }

    public void remove(UUID turnId) {
        budgets.remove(turnId);
    }
}
