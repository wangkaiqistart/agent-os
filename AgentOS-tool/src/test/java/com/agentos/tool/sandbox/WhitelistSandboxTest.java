package com.agentos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.SandboxViolationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class WhitelistSandboxTest {
    private void check(WhitelistSandbox sandbox, String url) {
        sandbox.enforce(new SandboxAction(ActionType.HTTP, url));
    }

    @Test
    void exactWildcardCaseAndIpAuthorizationAreExplicit() {
        var sandbox =
                new WhitelistSandbox(List.of("EXAMPLE.COM", "*.sub.example.com", "127.0.0.1"));
        check(sandbox, "https://example.com/a");
        check(sandbox, "http://a.sub.example.com/a");
        check(sandbox, "http://127.0.0.1:123/a");
        for (String url :
                List.of(
                        "https://sub.example.com",
                        "https://evil-example.com",
                        "https://example.com.evil.test",
                        "http://10.0.0.1/a")) {
            assertThatThrownBy(() -> check(sandbox, url))
                    .isInstanceOf(SandboxViolationException.class);
        }
    }

    @Test
    void emptyListNonHttpActionsAndUnsafeUrisFailClosedWithoutUrlSecrets() {
        var sandbox = new WhitelistSandbox(List.of("example.com"));
        assertThatThrownBy(() ->
                        check(
                                new WhitelistSandbox(List.of()),
                                "https://example.com/?key=SYNTHETIC_SECRET"))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageNotContaining("SYNTHETIC_SECRET");
        for (ActionType type :
                List.of(ActionType.FILE_READ, ActionType.FILE_WRITE, ActionType.SHELL)) {
            assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(type, "sensitive-target")))
                    .isInstanceOf(SandboxViolationException.class)
                    .hasMessageNotContaining("sensitive-target");
        }
        for (String url :
                List.of(
                        "file:///tmp/x",
                        "ftp://example.com/a",
                        "https://user:SYNTHETIC_SECRET@example.com/a",
                        "/relative",
                        "https://example.com@evil.test/?token=SYNTHETIC_SECRET")) {
            assertThatThrownBy(() -> check(sandbox, url))
                    .isInstanceOf(SandboxViolationException.class)
                    .hasMessageNotContaining("SYNTHETIC_SECRET");
        }
    }

    @Test
    void malformedAllowlistEntriesAreConfigurationErrors() {
        for (String entry :
                List.of(
                        "",
                        "https://example.com",
                        "example.com/path",
                        "example.com:80",
                        "*",
                        "*.127.0.0.1")) {
            assertThatThrownBy(() -> new WhitelistSandbox(List.of(entry)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
