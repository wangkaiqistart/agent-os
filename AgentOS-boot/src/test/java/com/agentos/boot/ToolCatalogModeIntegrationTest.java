package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolCatalogModeIntegrationTest {
    @TempDir Path root;

    @Test
    void directoryModeProtectsCompositeMcpReferencesWithoutExecutionBeans() throws Exception {
        Files.writeString(
                root.resolve("mcp_servers.yaml"),
                "servers:\n  - name: private-header\n    transport: sse\n    url: http://127.0.0.1:9\n"
                        + "    headers:\n      Authorization: Bearer ${PHASE14_CATALOG_TOKEN}\n"
                        + "      X-Business: prefix-${PHASE14_CATALOG_TOKEN}-${PHASE14_CATALOG_PART}-suffix\n");
        try (var context =
                new ToolCatalogContextLauncher()
                        .open(root, null, false, CompositeMcpConfiguration.class)) {
            var redactor = context.getBean(com.agentos.core.tool.Redactor.class);
            assertThat(redactor.redact("catalog-mcp-token-private catalog-mcp-part-private"))
                    .isEqualTo("*** ***");
            assertThat(
                            context.getBean(com.agentos.core.tool.ToolCatalogQuery.class)
                                    .snapshot(null)
                                    .tools())
                    .hasSize(11);
            assertThat(context.getBeansOfType(com.agentos.core.audit.ToolInvocationRecorder.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(com.agentos.core.turn.AgentService.class)).isEmpty();
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class CompositeMcpConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.agentos.tool.mcp.McpConfigurationLoader compositeMcpConfigurationLoader() {
            return new com.agentos.tool.mcp.McpConfigurationLoader(
                    java.util.Map.of(
                                    "PHASE14_CATALOG_TOKEN", "catalog-mcp-token-private",
                                    "PHASE14_CATALOG_PART", "catalog-mcp-part-private")
                            ::get);
        }
    }

    @Test
    void directoryModeRegistersCommandSecretsWithoutExecutingOrRequiringMissingReferences()
            throws Exception {
        Path config = root.resolve("private-catalog.yaml");
        String executable =
                Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString();
        Files.writeString(
                config,
                "CONVERGENCE_CATALOG_ENV_VALUE: catalog-reference-private\nagentos:\n  tools:\n    shell:\n      commands:\n        - executable: "
                        + executable
                        + "\n          env:\n            LITERAL: catalog-literal-private\n            REFERENCE: ${CONVERGENCE_CATALOG_ENV_VALUE}\n            MISSING: ${AGENTOS_TEST_ABSENT_CONVERGENCE_VALUE}\n");
        try (var context = new ToolCatalogContextLauncher().open(root, config, false)) {
            assertThat(
                            context.getBean(com.agentos.core.tool.Redactor.class)
                                    .redact("catalog-literal-private catalog-reference-private"))
                    .isEqualTo("*** ***");
            assertThat(
                            context.getBean(com.agentos.core.tool.ToolCatalogQuery.class)
                                    .snapshot(null)
                                    .tools())
                    .hasSize(11);
            assertThat(context.getBeansOfType(com.agentos.core.audit.ToolInvocationRecorder.class))
                    .isEmpty();
            assertThat(
                            context.getBeansOfType(
                                    org.springframework.ai.model.tool.ToolCallingManager.class))
                    .isEmpty();
        }
    }

    @Test
    void directoryModeWorksWithoutDatabaseProviderOrAuditAndServeRequiresToken() throws Exception {
        Path config = root.resolve("application.yaml");
        Files.writeString(config, "agentos:\n  tools:\n    query:\n      enabled: false\n");
        try (var context = new ToolCatalogContextLauncher().launch(root, config, false)) {
            assertThat(context.query().snapshot(null).tools()).hasSize(11);
        }
        assertThatThrownBy(() -> new ToolCatalogContextLauncher().launch(root, config, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actualServletAndNoneModeContainNoExecutionOrDatabaseBeans() throws Exception {
        for (boolean serve : java.util.List.of(false, true)) {
            int port;
            try (var socket = new java.net.ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Path config = root.resolve("catalog-" + serve + ".yaml");
            Files.writeString(
                    config,
                    "spring:\n  config:\n    import: optional:file:DO_NOT_READ.env[.properties]\nagentos:\n  tools:\n    query:\n      enabled: true\n      bind-address: 127.0.0.1\n      port: "
                            + port
                            + "\n      access-token: fixture-catalog-token\n");
            try (var context = new ToolCatalogContextLauncher().open(root, config, serve)) {
                assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
                assertThat(context.getBeansOfType(com.agentos.core.provider.ProviderService.class))
                        .isEmpty();
                assertThat(context.getBeansOfType(com.agentos.core.turn.AgentService.class))
                        .isEmpty();
                assertThat(
                                context.getBeansOfType(
                                        com.agentos.core.audit.ToolInvocationRecorder.class))
                        .isEmpty();
                assertThat(
                                context.getBeansOfType(
                                        org.springframework.ai.model.tool.ToolCallingManager.class))
                        .isEmpty();
                if (serve) {
                    try (var client = java.net.http.HttpClient.newHttpClient()) {
                        var uri = java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/tools");
                        var denied =
                                client.send(
                                        java.net.http.HttpRequest.newBuilder(uri).GET().build(),
                                        java.net.http.HttpResponse.BodyHandlers.ofString());
                        assertThat(denied.statusCode()).isEqualTo(401);
                        var accepted =
                                client.send(
                                        java.net.http.HttpRequest.newBuilder(uri)
                                                .header(
                                                        "Authorization",
                                                        "Bearer fixture-catalog-token")
                                                .GET()
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.ofString());
                        assertThat(accepted.statusCode()).isEqualTo(200);
                        assertThat(accepted.body())
                                .contains("read_file", "web_search", "UNCONFIGURED")
                                .doesNotContain("fixture-catalog-token");
                    }
                }
            }
        }
    }
}
