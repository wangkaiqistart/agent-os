package com.agentos.boot;

import com.agentos.core.tool.Redactor;
import com.agentos.provider.config.ProviderProperties;
import java.util.ArrayList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 仅装配层知道凭证来源；脱敏器只接收值集合，不读取 .env、不输出或保留密钥副本。 */
@Configuration(proxyBeanMethods = false)
public class ToolRedactionConfiguration {
    @Bean
    Redactor toolRedactor(ProviderProperties providers, Environment environment) {
        return new Redactor(() -> {
            var secrets = new ArrayList<String>();
            providers.getProviders().values().forEach(c -> secrets.add(c.getApiKey()));
            secrets.add(environment.getProperty("spring.datasource.password"));
            return secrets;
        });
    }
}
