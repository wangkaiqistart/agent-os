package com.agentos.channel.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CliIdentityTest {
    @Test
    void buildsCliSessionKey() {
        assertThat(new CliIdentity("u", "a").sessionKey().channel()).isEqualTo("cli");
    }
}
