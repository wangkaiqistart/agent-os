package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.tool.Redactor;
import com.agentos.provider.config.ProviderProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 验证 boot 提供所有已配置 Provider 和数据库凭证，core 不依赖这些配置类型。 */
class ToolAuditRedactionTest {
    @Test
    void providerAndDatabaseSecretsAreSuppliedDynamicallyWithoutLoggingValues() {
        var properties = new ProviderProperties();
        var a = new ProviderProperties.Connection();
        a.setApiKey("SYNTHETIC_PROVIDER_KEY");
        var b = new ProviderProperties.Connection();
        b.setApiKey("SYNTHETIC_SECOND_KEY");
        properties.setProviders(Map.of("a", a, "b", b));
        new ApplicationContextRunner()
                .withUserConfiguration(ToolRedactionConfiguration.class)
                .withBean(ProviderProperties.class, () -> properties)
                .withPropertyValues("spring.datasource.password=SYNTHETIC_DB_PASSWORD")
                .run(ctx -> {
                    var redactor = ctx.getBean(Redactor.class);
                    assertThat(
                                    redactor.redact(
                                            "SYNTHETIC_PROVIDER_KEY SYNTHETIC_SECOND_KEY SYNTHETIC_DB_PASSWORD"))
                            .isEqualTo("*** *** ***");
                    a.setApiKey("SYNTHETIC_ROTATED_KEY");
                    assertThat(redactor.redact("SYNTHETIC_ROTATED_KEY")).isEqualTo("***");
                });
    }
}
