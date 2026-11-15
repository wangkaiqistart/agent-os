package com.agentos.core.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agentos.workspace")
public record WorkspaceProperties(Path root) {
    public WorkspaceProperties {
        root = root == null ? Path.of(".AgentOS") : root;
    }
}
