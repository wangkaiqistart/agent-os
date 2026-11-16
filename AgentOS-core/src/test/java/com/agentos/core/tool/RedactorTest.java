package com.agentos.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RedactorTest {
    private final Redactor redactor =
            new Redactor(() -> List.of("known-private-value", "escaped\"secret"));

    @Test
    void redactsQueryHeadersKeyValueAndKnownValuesCaseInsensitively() {
        String raw =
                "https://host/x?KEY=url-key&Token=url-token&access_token=access&sign=sig&ok=keep "
                        + "Authorization: Bearer bearer-value\nAPI_KEY=field-value password: pass-value "
                        + "SeCrEt=secret-value token=token-value known-private-value";
        String safe = redactor.redact(raw);
        assertThat(safe)
                .doesNotContain(
                        "url-key",
                        "url-token",
                        "access&",
                        "sig&",
                        "bearer-value",
                        "field-value",
                        "pass-value",
                        "secret-value",
                        "token-value",
                        "known-private-value");
        assertThat(safe).contains("ok=keep", "***");
    }

    @Test
    void preservesJsonStructureAndHandlesEscapedNestedKeysAndValues() throws Exception {
        String raw =
                "{\"url\":\"https://h/?key=URL_SECRET\",\"nested\":[{\"AuThOrIzAtIoN\":\"Bearer HEADER_SECRET\",\"api_key\":\"JSON_SECRET\",\"apikey\":\"SECOND_SECRET\"}],\"password\":\"escaped\\\"secret\",\"ok\":42}";
        String safe = redactor.redact(raw);
        var json = JsonMapper.builder().build().readTree(safe);
        assertThat(json.path("ok").asInt()).isEqualTo(42);
        assertThat(json.path("nested").isArray()).isTrue();
        assertThat(json.path("password").asString()).isEqualTo("***");
        assertThat(safe)
                .doesNotContain(
                        "URL_SECRET", "HEADER_SECRET", "JSON_SECRET", "SECOND_SECRET", "escaped");
    }

    @Test
    void handlesMalformedJsonExceptionsNullAndEmptyWithoutLeaking() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
        assertThat(
                        redactor.redact(
                                "audit failed: password=known-private-value; token=unquoted-value"))
                .doesNotContain("known-private-value", "unquoted-value");
        assertThat(redactor.redact("{\"token\":\"broken-secret")).doesNotContain("broken-secret");
    }
}
