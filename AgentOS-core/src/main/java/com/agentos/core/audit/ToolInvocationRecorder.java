package com.agentos.core.audit;

public interface ToolInvocationRecorder {
    ToolInvocationRecord begin(ToolInvocationRecord record);

    void complete(ToolInvocationRecord record);

    void skipped(ToolInvocationRecord record);
}
