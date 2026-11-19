package com.agentos.tool.govern;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolCleanupUnconfirmedException;
import com.agentos.core.turn.LeaseLostException;
import com.agentos.core.turn.TurnDeadlineExceededException;
import java.util.List;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/** Manager 的异常边界：仅普通工具错误允许反馈模型，致命信号必须原样穿透。 */
public final class AgentOsToolExecutionExceptionProcessor
        implements ToolExecutionExceptionProcessor {
    private final Redactor redactor;

    public AgentOsToolExecutionExceptionProcessor() {
        this(new Redactor(List::of));
    }

    public AgentOsToolExecutionExceptionProcessor(Redactor redactor) {
        this.redactor = redactor;
    }

    public String process(ToolExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof AuditWriteException a) {
            throw a;
        }
        if (cause instanceof LeaseLostException l) {
            throw l;
        }
        if (cause instanceof TurnDeadlineExceededException t) {
            throw t;
        }
        if (cause instanceof ToolCleanupUnconfirmedException cleanup) {
            throw cleanup;
        }
        return redactor.redact(cause == null ? "工具执行失败" : cause.getMessage());
    }
}
