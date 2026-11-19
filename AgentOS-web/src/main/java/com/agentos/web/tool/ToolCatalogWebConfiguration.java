package com.agentos.web.tool;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** 专用过滤器先于MVC读取；目录模式没有通配CORS。 */
@Configuration(proxyBeanMethods = false)
@Conditional(ToolQueryEnabledCondition.class)
@EnableConfigurationProperties(ToolQueryProperties.class)
public class ToolCatalogWebConfiguration {
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<ToolCatalogAuthenticationFilter>
            toolCatalogAuthentication(
                    ToolQueryProperties properties, com.agentos.core.tool.Redactor redactor) {
        redactor.registerSecrets(List.of(properties.accessToken()));
        var registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                        new ToolCatalogAuthenticationFilter(properties));
        registration.setUrlPatterns(List.of("/api/v1/tools", "/api/v1/tools/*"));
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
