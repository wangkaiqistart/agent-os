package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonbMappingTest {
    @Test
    void invalidJsonIsKeptAsStringValue() {
        assertThat(MessageCodec.input("{invalid")).isEqualTo("{invalid");
        assertThat(MessageCodec.input(Map.of("ok", true))).isEqualTo(Map.of("ok", true));
    }
}
