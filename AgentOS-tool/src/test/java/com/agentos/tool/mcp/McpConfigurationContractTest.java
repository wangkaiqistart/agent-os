package com.agentos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigurationContractTest {
    @TempDir Path directory;

    @Test
    void registersOnlyDeclaredCredentialComponentsAndKeepsMissingSourcesSafe() throws Exception {
        var reads = new java.util.ArrayList<String>();
        var values =
                Map.of(
                        "TOKEN",
                        "raw-token-private",
                        "PART",
                        "raw-part-private",
                        "URL",
                        "https://fixture.example");
        var loader =
                new McpConfigurationLoader(name -> {
                    reads.add(name);
                    return values.get(name);
                });
        var redactor = new com.agentos.core.tool.Redactor(java.util.List::of);
        Path file = directory.resolve("composite.yaml");
        Files.writeString(
                file,
                """
                servers:
                  - name: header
                    transport: sse
                    url: ${URL}
                    headers:
                      Authorization: Bearer ${TOKEN}
                      X-Business: prefix-${TOKEN}-${PART}-suffix
                  - name: env
                    transport: stdio
                    command: /usr/bin/false
                    env:
                      COMPOSITE: prefix-${TOKEN}-${PART}-suffix
                      LITERAL: literal-private
                  - name: missing
                    transport: sse
                    url: https://fixture.example
                    headers:
                      Authorization: Bearer ${TOKEN}-${MISSING}
                """);
        var registered = new java.util.ArrayList<String>();
        var result =
                loader.load(
                        file,
                        value -> {
                            registered.add(value);
                            redactor.registerSecrets(java.util.List.of(value));
                        });
        assertThat(result.servers())
                .extracting(McpServerConfig::name)
                .containsExactly("header", "env");
        assertThat(result.failures())
                .containsExactly(new McpConfigurationLoader.Failure("mcp:missing", "references"));
        assertThat(result.failures().toString()).doesNotContain("MISSING", "raw-token", "raw-part");
        assertThat(reads).containsOnly("TOKEN", "PART", "URL", "MISSING");
        assertThat(registered)
                .contains(
                        "raw-token-private",
                        "raw-part-private",
                        "Bearer raw-token-private",
                        "prefix-raw-token-private-raw-part-private-suffix",
                        "literal-private")
                .doesNotContain(values.get("URL"), "/usr/bin/false");
        assertThat(redactor.redact("raw-token-private raw-part-private literal-private"))
                .isEqualTo("*** *** ***");
        assertThat(
                        redactor.redact(
                                "Bearer raw-token-private prefix-raw-token-private-raw-part-private-suffix"))
                .isEqualTo("*** ***");
    }

    @Test
    void duplicateSourcesAndInexactPoliciesAreRejectedWithoutOverwritingValidSources()
            throws Exception {
        var loader = new McpConfigurationLoader(Map.<String, String>of()::get);
        Path file = directory.resolve("config.yaml");
        for (String bad :
                java.util.List.of(
                        "max-result-chars: bogus",
                        "max-result-chars: 100.5",
                        "tools: {echo: {unknown: true}}",
                        "tools: {echo: {timeout: 0s}}")) {
            Files.writeString(
                    file,
                    "servers:\n  - name: good\n    transport: sse\n    url: https://fixture.example\n  - name: bad\n    transport: sse\n    url: https://fixture.example\n    "
                            + bad
                            + "\n");
            var result = loader.load(file);
            assertThat(result.servers()).extracting(McpServerConfig::name).containsExactly("good");
            assertThat(result.failures()).hasSize(1);
        }
        Files.writeString(
                file,
                "servers:\n  - name: repeated\n    transport: sse\n    url: https://fixture.example\n  - name: repeated\n    transport: sse\n    url: https://fixture.example\n");
        assertThat(loader.load(file).servers()).isEmpty();
        assertThat(loader.load(file).failures()).hasSize(1);
    }

    @Test
    void absentFileMeansNoSourcesButInvalidYamlIsNotAnEmptyConfiguration() throws Exception {
        var loader = new McpConfigurationLoader(Map.<String, String>of()::get);
        assertThat(loader.load(directory.resolve("missing")).servers()).isEmpty();
        assertThat(loader.load(directory.resolve("missing")).failures()).isEmpty();
        for (String yaml :
                java.util.List.of(
                        "servers: [broken", "servers: []\nservers: []", "!!java.lang.Runtime {}")) {
            Files.writeString(directory.resolve("servers.yaml"), yaml);
            assertThat(loader.load(directory.resolve("servers.yaml")).failures()).isNotEmpty();
        }
    }

    @Test
    void eachTransportHasMutuallyExclusiveFieldsAndSafeEnvironmentResolution() throws Exception {
        var loader =
                new McpConfigurationLoader(
                        Map.of(
                                        "FIXTURE_URL",
                                        "https://fixture.example",
                                        "FIXTURE_TOKEN",
                                        "configured-secret-sentinel")
                                ::get);
        Path file = directory.resolve("mcp.yaml");
        Files.writeString(
                file,
                "servers:\n  - name: news\n    transport: sse\n    url: ${FIXTURE_URL}\n    sse-endpoint: /sse\n    headers:\n      Authorization: ${FIXTURE_TOKEN}\n    tools:\n      echo:\n        idempotent: true\n        timeout: 100ms\n");
        var valid = loader.load(file);
        assertThat(valid.failures()).isEmpty();
        assertThat(valid.servers()).hasSize(1);
        assertThat(valid.servers().getFirst().headers())
                .containsEntry("Authorization", "configured-secret-sentinel");
        assertThat(valid.servers().getFirst().tools().get("echo").idempotent()).isTrue();
        for (String addition :
                java.util.List.of(
                        "    command: /bin/secret-program\n",
                        "    env: {PRIVATE_ENV: secret-value}\n")) {
            Files.writeString(
                    file,
                    "servers:\n  - name: news\n    transport: sse\n    url: ${FIXTURE_URL}\n"
                            + addition);
            var invalid = loader.load(file);
            assertThat(invalid.servers()).isEmpty();
            assertThat(invalid.failures()).hasSize(1);
            assertThat(invalid.failures().toString())
                    .doesNotContain(
                            "secret-value",
                            "secret-program",
                            "fixture.example",
                            "configured-secret-sentinel");
        }
        for (String name : java.util.List.of("UPPER", "a_b", "x".repeat(21))) {
            Files.writeString(
                    file,
                    "servers:\n  - name: "
                            + name
                            + "\n    transport: sse\n    url: ${FIXTURE_URL}\n");
            assertThat(loader.load(file).failures()).isNotEmpty();
        }
        Files.writeString(
                file, "servers:\n  - name: news\n    transport: sse\n    url: ${MISSING_URL}\n");
        assertThat(loader.load(file).failures().toString()).doesNotContain("MISSING_URL");
    }
}
