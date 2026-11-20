package com.agentos.core.turn;

import java.util.UUID;

/**
 * 完成收尾后交给渠道的结果，把业务执行状态和持久化确认状态分开。
 *
 * @param turnId 可用于关联数据库轮次与审计的标识
 * @param status 业务终态；FAILED/TIMED_OUT 也可能作为正常方法返回值出现
 * @param text 给用户展示的回答或固定失败说明
 * @param iterations 循环层报告的迭代次数；异常路径当前返回 0，不等于外呼次数审计
 * @param persistenceStatus 存储收尾是否得到确认，不等同于模型是否回答成功
 * @param failureCode 非成功、非取消时的稳定错误码；成功与取消时为 null
 */
public record TurnResult(
        UUID turnId,
        TurnStatus status,
        String text,
        int iterations,
        PersistenceStatus persistenceStatus,
        TurnFailureCode failureCode) {
    public TurnResult {
        text = text == null ? "" : text;
        persistenceStatus =
                persistenceStatus == null ? PersistenceStatus.CONFIRMED : persistenceStatus;
    }

    /** 兼容既有调用点：不带错误码。 */
    public TurnResult(
            UUID turnId,
            TurnStatus status,
            String text,
            int iterations,
            PersistenceStatus persistenceStatus) {
        this(turnId, status, text, iterations, persistenceStatus, null);
    }
}
