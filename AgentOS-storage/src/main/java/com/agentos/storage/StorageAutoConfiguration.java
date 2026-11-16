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

/**
 * 数据存储模块的装配入口，显式注册 core 端口的 JPA 实现，避免依赖 boot 的默认包扫描。
 *
 * <p>三种机制分工不同：EntityScan 找实体映射，EnableJpaRepositories 创建 Repository 代理，
 * Bean 方法注册应用使用的存储对象；方法参数由容器按类型注入。事务由 Spring 代理管理。
 *
 * <p>在数据源/JPA/Flyway 的自动配置之后处理，存在 DataSource 才启用本配置；
 * 在 Provider 配置前声明数据库审计 Bean，使它优先于 Provider 的日志后备实现。
 */
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
    /** Bean 默认名称为方法名；对 core 暴露接口类型，隔离 JPA 和 JDBC 细节。 */
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
        // 正式路径必须传入 JDBC，以数据库时钟协调跨实例租约。
        return new JpaTurnStore(sessions, turns, messages, jdbc);
    }

    @Bean
    MessageStore messageStore(SessionMessageRepository messages) {
        return new JpaMessageStore(messages);
    }

    @Bean
    @ConditionalOnMissingBean(LlmCallRecorder.class)
    LlmCallRecorder llmCallRecorder(LlmCallRepository repository) {
        // 自定义 Recorder 已存在时退让；这里的条件不意味着发生数据库错误时会回退到日志。
        return new JpaLlmCallRecorder(repository);
    }

    @Bean
    @ConditionalOnMissingBean(com.agentos.core.audit.ToolInvocationRecorder.class)
    com.agentos.core.audit.ToolInvocationRecorder toolInvocationRecorder(
            com.agentos.storage.session.ToolInvocationRepository repository,
            com.agentos.core.tool.Redactor redactor,
            org.springframework.transaction.PlatformTransactionManager manager) {
        return new com.agentos.storage.audit.JpaToolInvocationRecorder(
                repository, redactor, manager);
    }
}
