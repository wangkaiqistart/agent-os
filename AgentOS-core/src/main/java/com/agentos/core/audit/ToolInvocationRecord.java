package com.agentos.core.audit;

import java.util.UUID;

/**
 * 工具调用审计的不可变载荷契约，当前尚未由正式工具执行链产生。
 *
 * <p>sessionId、turnId、iteration 关联会话与模型迭代；toolCallId 是模型给出的工具调用 ID，
 * 与 turnId、iteration 组合用于区分同一轮中的不同工具动作。
 *
 * <p>inputJson、resultText、errorMessage 可能携带敏感业务数据，记录前必须由治理层脱敏。
 * resultChars/resultTruncated 描述结果截断，attempts/durationMs 描述尝试次数和总耗时；
 * idempotent 表达是否允许重试，不应由成功与否倒推。
 */
public record ToolInvocationRecord(
        UUID sessionId,
        UUID turnId,
        int iteration,
        String toolCallId,
        String toolName,
        String source,
        String inputJson,
        String resultText,
        Integer resultChars,
        boolean resultTruncated,
        String status,
        String errorMessage,
        boolean idempotent,
        int attempts,
        Long durationMs) {}
