package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProviderNameCatalogTest {
    @TempDir Path directory;

    @Test
    void cachesNamesWithoutExposingConfigValues() throws Exception {
        Path config = directory.resolve("application.yaml");
        Files.writeString(
                config,
                "agentos:\n  providers:\n    primary:\n      api-key: synthetic-secret\n    custom: {}\n");
        var catalog = new LocalProviderNameCatalog(config);
        assertThat(catalog.names()).containsExactly("primary", "custom");
        assertThat(catalog.contains("primary")).isTrue();
        assertThat(catalog.contains("unknown")).isFalse();
        assertThat(catalog.configurationPath()).contains(config.toAbsolutePath().normalize());
    }

    @Test
    void unavailableCatalogFailsBeforeBusinessCommandCanUseIt() throws Exception {
        var catalog = new LocalProviderNameCatalog(directory.resolve("missing.yaml"));
        assertThatThrownBy(catalog::names)
                .isInstanceOf(ProviderNameProjectionLoader.ConfigurationException.class);
    }
}
