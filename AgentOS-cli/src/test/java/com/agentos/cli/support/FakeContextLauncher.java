package com.agentos.cli.support;

import com.agentos.cli.ContextLauncher;
import com.agentos.core.session.SessionManager;
import com.agentos.core.turn.AgentService;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 不启动 Spring 的命令测试启动器。 */
public final class FakeContextLauncher implements ContextLauncher {
    private final AgentService service;
    private final SessionManager sessions;
    private final AtomicReference<Path> workspace = new AtomicReference<>();
    private final AtomicReference<Path> config = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public FakeContextLauncher(AgentService service, SessionManager sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @Override
    public RunningContext launch(Path workspaceRoot, Path configPath) {
        workspace.set(workspaceRoot);
        config.set(configPath);
        return new RunningContext() {
            @Override
            public AgentService agentService() {
                return service;
            }

            @Override
            public SessionManager sessionManager() {
                return sessions;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    @Override
    public RunningContext launch(Path workspaceRoot) {
        return launch(workspaceRoot, null);
    }

    public Path workspace() {
        return workspace.get();
    }

    public Path config() {
        return config.get();
    }

    public boolean closed() {
        return closed.get();
    }
}
