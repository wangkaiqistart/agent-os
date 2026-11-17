package com.agentos.cli;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/** 只暴露可被 Agent Profile 引用的 Provider 名称，不暴露连接配置或凭据。 */
public interface ProviderNameCatalog {
    /** 返回不可变的非敏感名称集合。 */
    Set<String> names();

    /** 返回实际使用的本地配置文件；classpath 配置没有文件路径时返回空。 */
    default Optional<Path> configurationPath() {
        return Optional.empty();
    }

    default boolean contains(String name) {
        return name != null && names().contains(name);
    }
}
