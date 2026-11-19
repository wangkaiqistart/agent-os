package com.agentos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentSettingsTest {
    @Test
    void defaultsMatchContract() {
        assertThat(AgentSettings.defaults())
                .isEqualTo(new AgentSettings(10, 20, 32_000, Duration.ofSeconds(300)));
    }

    @Test
    void validatesBoundsAndTokenReserve() {
        assertThat(new AgentSettings(1, 0, 1, Duration.ofSeconds(1)).effectiveHistoryTokens())
                .isZero();
        assertThat(new AgentSettings(1, 1, 101, Duration.ofSeconds(1)).effectiveHistoryTokens())
                .isEqualTo(90);
        assertThatThrownBy(() -> new AgentSettings(0, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
