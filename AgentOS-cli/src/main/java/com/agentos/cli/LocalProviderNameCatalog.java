package com.agentos.cli;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** 基于最终配置文件名称投影的本地 Provider 目录。 */
public final class LocalProviderNameCatalog implements ProviderNameCatalog {
    private final ProviderNameProjectionLoader loader;
    private final Path executionDirectory;
    private final Supplier<Path> explicitConfig;
    private final Function<String, String> environment;
    private final Path userHome;
    private volatile Set<String> names;

    public LocalProviderNameCatalog(
            ProviderNameProjectionLoader loader,
            Path executionDirectory,
            Path explicitConfig,
            Function<String, String> environment,
            Path userHome) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.executionDirectory = Objects.requireNonNull(executionDirectory, "executionDirectory");
        this.explicitConfig = () -> explicitConfig;
        this.environment = Objects.requireNonNull(environment, "environment");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
    }

    public LocalProviderNameCatalog(Path config) {
        this(
                new ProviderNameProjectionLoader(),
                Path.of(".").toAbsolutePath().normalize(),
                Objects.requireNonNull(config, "config"),
                System::getenv,
                Path.of(System.getProperty("user.home", ".")));
    }

    public LocalProviderNameCatalog(
            ProviderNameProjectionLoader loader,
            Path executionDirectory,
            Supplier<Path> explicitConfig,
            Function<String, String> environment,
            Path userHome) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.executionDirectory = Objects.requireNonNull(executionDirectory, "executionDirectory");
        this.explicitConfig = Objects.requireNonNull(explicitConfig, "explicitConfig");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
    }

    @Override
    public Set<String> names() {
        Set<String> current = names;
        if (current == null) {
            synchronized (this) {
                current = names;
                if (current == null) {
                    var resolution =
                            loader.resolve(
                                    executionDirectory,
                                    explicitConfig.get(),
                                    environment,
                                    userHome);
                    current = loader.load(resolution);
                    names = current;
                }
            }
        }
        return current;
    }

    @Override
    public Optional<Path> configurationPath() {
        names();
        Path explicit = explicitConfig.get();
        if (explicit != null) {
            return Optional.of(explicit.toAbsolutePath().normalize());
        }
        var resolution = loader.resolve(executionDirectory, null, environment, userHome);
        return Optional.ofNullable(resolution.path());
    }
}
