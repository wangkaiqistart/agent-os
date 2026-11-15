package com.agentos.core.audit;

import java.util.UUID;

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
