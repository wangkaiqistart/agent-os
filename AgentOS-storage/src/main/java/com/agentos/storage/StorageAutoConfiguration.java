package com.agentos.storage;

import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.SessionManager;
import com.agentos.core.session.TurnStore;
import com.agentos.storage.audit.JpaLlmCallRecorder;
import com.agentos.storage.session.AgentTurnRepository;
import com.agentos.storage.session.JpaMessageStore;
import com.agentos.storage.session.JpaSessionManager;
import com.agentos.storage.session.JpaTurnStore;
import com.agentos.storage.session.LlmCallRepository;
import com.agentos.storage.session.SessionMessageRepository;
import com.agentos.storage.session.SessionRepository;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 显式注册 storage 端口和实体，避免依赖 boot 的默认包扫描。 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        },
        beforeName = "com.agentos.provider.config.ProviderAutoConfiguration")
@ConditionalOnBean(DataSource.class)
@EntityScan("com.agentos.storage.session")
@EnableJpaRepositories("com.agentos.storage.session")
public class StorageAutoConfiguration {
    @Bean
    SessionManager sessionManager(
            SessionRepository repository, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new JpaSessionManager(repository, jdbc);
    }

    @Bean
    TurnStore turnStore(
            SessionRepository sessions,
            AgentTurnRepository turns,
            SessionMessageRepository messages,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new JpaTurnStore(sessions, turns, messages, jdbc);
    }

    @Bean
    MessageStore messageStore(SessionMessageRepository messages) {
        return new JpaMessageStore(messages);
    }

    @Bean
    @ConditionalOnMissingBean(LlmCallRecorder.class)
    LlmCallRecorder llmCallRecorder(LlmCallRepository repository) {
        return new JpaLlmCallRecorder(repository);
    }
}
