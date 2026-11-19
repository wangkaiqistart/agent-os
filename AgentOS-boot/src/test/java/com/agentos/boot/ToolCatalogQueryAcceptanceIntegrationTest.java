package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.tool.ToolCatalogQuery;
import com.agentos.core.tool.ToolCatalogView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class ToolCatalogQueryAcceptanceIntegrationTest {
    @TempDir Path root;
    private static final AtomicInteger reads = new AtomicInteger();
    private static Map<String, ToolCatalogView> views;

    @Test
    void cliAndAuthenticatedHttpUseSameFrozenProjectionAndCreateNoBusinessState() throws Exception {
        Files.createDirectories(root.resolve("agents/demo"));
        Files.writeString(
                root.resolve("agents/demo/AGENT.md"),
                "---\nname: demo\nprovider:\n  model: fixture\ntools: [read_file, web_search]\n---\n测试\n");
        Files.writeString(
                root.resolve("mcp_servers.yaml"),
                "servers:\n  - name: pending\n    transport: streamable-http\n    url: ${AGENTOS_INTENTIONALLY_UNSET_CATALOG_FIXTURE}\n    endpoint: /mcp\n");
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path config = root.resolve("catalog.yaml");
        Files.writeString(
                config,
                "agentos:\n  tools:\n    query:\n      enabled: true\n      bind-address: 0.0.0.0\n      port: "
                        + port
                        + "\n      access-token: fixture-catalog-token\n");
        try (var context =
                        new ToolCatalogContextLauncher()
                                .open(root, config, true, FrozenQuery.class);
                var client = java.net.http.HttpClient.newHttpClient()) {
            views = null;
            var output = new java.io.StringWriter();
            com.agentos.cli.ContextLauncher launcher =
                    new com.agentos.cli.ContextLauncher() {
                        public RunningContext launch(Path path) {
                            throw new AssertionError("不得聊天");
                        }

                        public CatalogContext launchCatalog(
                                Path path, Path configured, boolean serve) {
                            return () -> context.getBean(ToolCatalogQuery.class);
                        }
                    };
            for (String agent : java.util.List.of("global", "demo")) {
                reads.set(0);
                var uri =
                        java.net.URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        + "/api/v1/tools"
                                        + (agent.equals("global") ? "" : "?agent=demo"));
                for (String token : java.util.List.of("", "wrong")) {
                    var request = java.net.http.HttpRequest.newBuilder(uri);
                    if (!token.isEmpty()) {
                        request.header("Authorization", "Bearer " + token);
                    }
                    var response =
                            client.send(
                                    request.GET().build(),
                                    java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(401);
                }
                assertThat(reads).hasValue(0);
                var http =
                        client.send(
                                java.net.http.HttpRequest.newBuilder(uri)
                                        .header("Authorization", "Bearer fixture-catalog-token")
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(http.statusCode()).isEqualTo(200);
                var cli = com.agentos.cli.AgentOSCli.commandLine(launcher, root, key -> null);
                cli.setOut(new java.io.PrintWriter(output));
                cli.setErr(new java.io.PrintWriter(output));
                var args =
                        new java.util.ArrayList<>(
                                java.util.List.of(
                                        "tool", "list", "--workspace", root.toString(), "--json"));
                if (!agent.equals("global")) {
                    args.addAll(java.util.List.of("--agent", agent));
                }
                output.getBuffer().setLength(0);
                assertThat(cli.execute(args.toArray(String[]::new))).isZero();
                var json = tools.jackson.databind.json.JsonMapper.builder().build();
                assertContract(json.readTree(http.body()).path("data"));
                assertThat(json.readTree(output.toString()))
                        .isEqualTo(json.readTree(http.body()).path("data"));
                assertThat(reads).hasValue(2);
                assertThat(output.toString()).contains("mcp:pending");
                assertThat(output.toString())
                        .doesNotContain(
                                "accessToken",
                                "inputSchema",
                                "callback",
                                "AGENTOS_INTENTIONALLY_UNSET",
                                "http://");
            }
            assertThat(context.getBeansOfType(com.agentos.core.session.SessionManager.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(com.agentos.core.turn.AgentService.class)).isEmpty();
            assertThat(context.getBeansOfType(com.agentos.core.provider.ProviderService.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(com.agentos.core.audit.ToolInvocationRecorder.class))
                    .isEmpty();
        }
    }

    private void assertContract(tools.jackson.databind.JsonNode catalog) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        while (project != null
                && !Files.exists(
                        project.resolve(
                                "specs/005-expand-tool-capabilities/contracts/tool-query.openapi.yaml"))) {
            project = project.getParent();
        }
        assertThat(project).isNotNull();
        var loader =
                new org.yaml.snakeyaml.Yaml(
                        new org.yaml.snakeyaml.constructor.SafeConstructor(
                                new org.yaml.snakeyaml.LoaderOptions()));
        var api =
                (Map<?, ?>)
                        loader.load(
                                Files.readString(
                                        project.resolve(
                                                "specs/005-expand-tool-capabilities/contracts/tool-query.openapi.yaml")));
        var schemas = (Map<?, ?>) ((Map<?, ?>) api.get("components")).get("schemas");
        assertThat(catalog.propertyNames())
                .containsExactlyInAnyOrderElementsOf(
                        ((Map<?, ?>) ((Map<?, ?>) schemas.get("Catalog")).get("properties"))
                                .keySet().stream().map(Object::toString).toList());
        for (var item : catalog.path("tools")) {
            assertThat(item.propertyNames())
                    .containsExactlyInAnyOrderElementsOf(
                            ((Map<?, ?>) ((Map<?, ?>) schemas.get("ToolItem")).get("properties"))
                                    .keySet().stream().map(Object::toString).toList());
        }
        for (var source : catalog.path("sources")) {
            assertThat(source.propertyNames())
                    .containsExactlyInAnyOrderElementsOf(
                            ((Map<?, ?>) ((Map<?, ?>) schemas.get("SourceItem")).get("properties"))
                                    .keySet().stream().map(Object::toString).toList());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FrozenQuery {
        @Bean
        @Primary
        ToolCatalogQuery frozen(
                com.agentos.tool.registry.ToolRegistry registry,
                com.agentos.core.agent.AgentLoader loader,
                com.agentos.core.tool.Redactor redactor) {
            return agent -> {
                reads.incrementAndGet();
                // 第一次请求时来源维护已完成，随后两个入口复用同一版本与时间。
                String key = agent == null ? "global" : agent;
                synchronized (FrozenQuery.class) {
                    if (views == null) {
                        var query =
                                new com.agentos.tool.registry.DefaultToolCatalogQuery(
                                        registry, loader, redactor);
                        views =
                                Map.of(
                                        "global",
                                        query.snapshot(null),
                                        "demo",
                                        query.snapshot("demo"));
                    }
                    return views.get(key);
                }
            };
        }
    }
}
