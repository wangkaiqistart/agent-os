package com.agentos.web.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentos.core.tool.ToolCatalogView;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ToolCatalogAuthenticationTest {
    @Test
    void localAndRemoteRequireOneValidHeaderBeforeAnyCatalogOrAgentRead() throws Exception {
        String token = "fixture-catalog-token";
        var reads = new AtomicInteger();
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new ToolCatalogController(agent -> {
                                    reads.incrementAndGet();
                                    return new ToolCatalogView(
                                            "v1",
                                            java.time.Instant.now(),
                                            agent,
                                            List.of(),
                                            List.of());
                                }))
                        .addFilters(
                                new ToolCatalogAuthenticationFilter(
                                        new ToolQueryProperties(true, "127.0.0.1", 8080, token)))
                        .build();
        for (String host : List.of("127.0.0.1", "192.0.2.10")) {
            reads.set(0);
            for (int mode = 0; mode < 5; mode++) {
                var request =
                        get("/api/v1/tools")
                                .with(r -> {
                                    r.setRemoteAddr(host);
                                    return r;
                                })
                                .param("agent", "demo");
                if (mode == 1) {
                    request.header("Authorization", "Bearer wrong-token");
                }
                if (mode == 2) {
                    request.header("Authorization", "Bearer " + token, "Bearer " + token);
                }
                if (mode == 3) {
                    request.header("Authorization", "Basic " + token);
                }
                if (mode == 4) {
                    request.header("Authorization", "Bearer " + token).param("access_token", token);
                }
                var result = mvc.perform(request).andExpect(status().isUnauthorized()).andReturn();
                assertThat(result.getResponse().getContentAsString())
                        .contains("UNAUTHORIZED", "未认证")
                        .doesNotContain(token, "demo");
                assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(reads).hasValue(0);
            }
            mvc.perform(
                            get("/api/v1/tools")
                                    .with(r -> {
                                        r.setRemoteAddr(host);
                                        return r;
                                    })
                                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            assertThat(reads).hasValue(1);
        }
        reads.set(0);
        var accepted =
                mvc.perform(
                                get("/api/v1/tools")
                                        .header("Authorization", "Bearer " + token)
                                        .header("Origin", "https://untrusted.example"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(reads).hasValue(1);
        assertThat(accepted.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void missingTokenNeverFallsBackToAnonymous() throws Exception {
        var reads = new AtomicInteger();
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new ToolCatalogController(agent -> {
                                    reads.incrementAndGet();
                                    return null;
                                }))
                        .addFilters(
                                new ToolCatalogAuthenticationFilter(
                                        new ToolQueryProperties(false, "127.0.0.1", 8080, "")))
                        .build();
        mvc.perform(get("/api/v1/tools").header("Authorization", "Bearer anything"))
                .andExpect(status().isUnauthorized());
        assertThat(reads).hasValue(0);
    }
}
