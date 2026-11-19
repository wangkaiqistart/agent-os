package com.agentos.tool.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.Redactor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessEnvironmentPolicyTest {
    @Test
    void earlySecretsResolveOnlyDeclaredReferencesAndKeepMissingCommandsUnavailable()
            throws Exception {
        var explicit =
                Map.of(
                        "LITERAL",
                        "literal-private",
                        "COMPOSED",
                        "prefix-${BUSINESS}-suffix",
                        "MISSING",
                        "${ABSENT}-${BUSINESS}");
        var properties =
                new ShellToolProperties(
                        List.of(
                                new ShellToolProperties.Command(
                                        ShellToolsContractTest.javaExecutable(),
                                        List.of(Path.of(".")),
                                        explicit)),
                        List.of("LANG"),
                        65536,
                        Duration.ofSeconds(30),
                        10000);
        var lookedUp = new ArrayList<String>();
        var policy =
                new ProcessEnvironmentPolicy(
                        properties,
                        name -> {
                            lookedUp.add(name);
                            return Map.of(
                                            "BUSINESS",
                                            "reference-private",
                                            "LANG",
                                            "C",
                                            "UNDECLARED",
                                            "host-private")
                                    .get(name);
                        });
        var redactor = new Redactor(List::of);
        redactor.registerSecrets(policy.configuredSecrets());
        assertThat(lookedUp).containsOnly("BUSINESS", "ABSENT");
        assertThat(
                        redactor.redact(
                                "literal-private reference-private prefix-reference-private-suffix host-private"))
                .isEqualTo("*** *** *** host-private");
        assertThat(policy.build(Map.of("VALUE", "${BUSINESS}")))
                .containsEntry("VALUE", "reference-private")
                .containsEntry("LANG", "C");
        assertThatThrownBy(() -> policy.build(explicit))
                .hasMessageContaining("UNCONFIGURED")
                .hasMessageNotContaining("ABSENT")
                .hasMessageNotContaining("reference-private");
    }
}
