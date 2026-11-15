package com.agentos.tool.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpToolPropertiesTest {
    @Test
    void defaultsAndBoundsAreApplied() {
        assertThat(new HttpToolProperties(List.of(), null, 0, 0, null).maxRedirects()).isEqualTo(3);
        assertThatThrownBy(
                        () -> new HttpToolProperties(List.of(), "ua", 4, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
