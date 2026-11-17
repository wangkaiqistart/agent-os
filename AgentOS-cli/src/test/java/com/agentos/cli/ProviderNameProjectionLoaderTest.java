package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderNameProjectionLoaderTest {
    @TempDir Path directory;

    @Test
    void projectsOnlyProviderKeysAndSupportsExplicitPath() throws Exception {
        Path config = directory.resolve("selected.yaml");
        Files.writeString(
                config,
                "agentos:\n  providers:\n    primary:\n      api-key: synthetic-secret\n      base-url: https://secret.invalid\n    local-test:\n      model: private-model\n");
        var loader = new ProviderNameProjectionLoader();
        assertThat(loader.load(config)).containsExactly("primary", "local-test");
    }

    @Test
    void explicitFailureDoesNotFallBackToOtherCandidates() throws Exception {
        Files.writeString(
                directory.resolve("application.yaml"), "agentos:\n  providers:\n    primary: {}\n");
        var loader = new ProviderNameProjectionLoader();
        assertThatThrownBy(() ->
                        loader.resolve(
                                directory,
                                directory.resolve("missing.yaml"),
                                key -> null,
                                directory))
                .isInstanceOf(ProviderNameProjectionLoader.ConfigurationException.class);
    }

    @Test
    void defaultSearchOrderUsesExecutionThenConfigThenXdgThenHome() throws Exception {
        Path xdg = directory.resolve("xdg");
        Path home = directory.resolve("home");
        Files.createDirectories(directory.resolve("config"));
        Files.createDirectories(xdg.resolve("agentos"));
        Files.createDirectories(home.resolve(".config/agentos"));
        Files.writeString(
                directory.resolve("config/application.yaml"),
                "agentos:\n  providers:\n    from-config: {}\n");
        Files.writeString(
                xdg.resolve("agentos/application.yaml"),
                "agentos:\n  providers:\n    from-xdg: {}\n");
        var loader = new ProviderNameProjectionLoader();
        var resolution =
                loader.resolve(
                        directory, null, Map.of("XDG_CONFIG_HOME", xdg.toString())::get, home);
        assertThat(resolution.path()).isEqualTo(directory.resolve("config/application.yaml"));
        assertThat(loader.load(resolution)).containsExactly("from-config");
    }

    @Test
    void malformedOrMissingProvidersAreConfigurationErrors() throws Exception {
        Path malformed = directory.resolve("malformed.yaml");
        Files.writeString(malformed, "agentos: [broken");
        var loader = new ProviderNameProjectionLoader();
        assertThatThrownBy(() -> loader.load(malformed))
                .isInstanceOf(ProviderNameProjectionLoader.ConfigurationException.class);
        Path missing = directory.resolve("missing.yaml");
        assertThatThrownBy(() -> loader.load(missing))
                .isInstanceOf(ProviderNameProjectionLoader.ConfigurationException.class);
    }
}
