package com.agentos.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * init 专用的离线模型解析器，不装配 Spring，也不读取 Provider/数据库连接。
 * 已提供的高优先级值即使无效也不回退，避免悄悄用另一个模型生成 Agent。
 */
public final class InitModelResolver {
    private static final String DEFAULT_PROVIDER = "primary";
    private final Function<String, String> environment;
    private final Path directory;

    public InitModelResolver(Function<String, String> environment, Path directory) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** 只接受显式模型；null 表示缺失，空字符串属于无效的显式输入。 */
    public DefaultAgentOptions resolve(String model, String provider) throws IOException {
        if (model == null) {
            throw new IllegalArgumentException("必须显式提供 --model");
        }
        return new DefaultAgentOptions(model, provider == null ? DEFAULT_PROVIDER : provider);
    }
}
