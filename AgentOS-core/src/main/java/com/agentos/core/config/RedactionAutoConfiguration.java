package com.agentos.core.config;

import com.agentos.core.tool.Redactor;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 无 Provider 依赖的默认脱敏器，boot 可用安全密钥 Supplier 替换。 */
@AutoConfiguration(
        beforeName = {
            "com.agentos.storage.StorageAutoConfiguration",
            "com.agentos.tool.ToolAutoConfiguration"
        })
public class RedactionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Redactor.class)
    Redactor redactor() {
        return new Redactor(List::of);
    }
}
