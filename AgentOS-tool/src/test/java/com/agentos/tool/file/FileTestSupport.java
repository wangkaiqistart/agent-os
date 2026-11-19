package com.agentos.tool.file;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnExecutionBudget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

final class FileTestSupport {
    static FileAccessPolicy policy(Path workspace) {
        return new FileAccessPolicy(
                workspace, FileToolProperties.defaults(List.of(workspace), List.of(workspace)));
    }

    static ToolExecutionScope scope() {
        return new ToolExecutionScope(
                null,
                "file-test",
                TurnExecutionBudget.start(Duration.ofSeconds(5)),
                Duration.ofSeconds(5),
                () -> {});
    }

    static ToolContext context(ToolExecutionScope scope) {
        return new ToolContext(Map.of(ToolContextKeys.TOOL_SCOPE, scope));
    }

    private FileTestSupport() {}
}
