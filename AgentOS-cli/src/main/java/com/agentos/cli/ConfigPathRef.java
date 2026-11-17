package com.agentos.cli;

import java.nio.file.Path;

/** Picocli 根命令与延迟执行子命令之间共享的全局配置路径。 */
final class ConfigPathRef {
    private Path value;

    Path get() {
        return value;
    }

    void set(Path value) {
        this.value = value;
    }
}
