package com.agentos.storage.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * session_messages 表映射：消息逐条保存，通过 sessionId、turnId、iteration 还原所属位置。
 *
 * <p>USER 在创建轮次时以 iteration=0 写入，ASSISTANT/TOOL 使用实际模型迭代序号。
 * 工具结构字段由 JpaMessageStore 写入，并可按完整轮次恢复。
 */
@Entity
@Table(name = "session_messages")
public class SessionMessageEntity {
    // 数据库生成递增序号，查询用它保持保存顺序；不同于业务生成的会话/轮次 UUID。
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "turn_id", nullable = false)
    private UUID turnId;

    @Column(nullable = false)
    private int iteration;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "text")
    private String content;

    // JdbcTypeCode 指定 Hibernate 的 JSON 编解码；columnDefinition 对齐 PostgreSQL jsonb 列。
    // 这不负责字段脱敏，也不替代 Flyway 建表。
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private Object toolCalls;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_responses", columnDefinition = "jsonb")
    private Object toolResponses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 供 JPA 读取行数据；正常追加消息使用下面的构造器。 */
    protected SessionMessageEntity() {}

    public SessionMessageEntity(
            UUID sessionId,
            UUID turnId,
            int iteration,
            String type,
            String content,
            Object toolCalls,
            Object toolResponses,
            Map<String, Object> metadata,
            Instant createdAt) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.iteration = iteration;
        this.type = type;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolResponses = toolResponses;
        this.metadata = metadata;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getTurnId() {
        return turnId;
    }

    public int getIteration() {
        return iteration;
    }

    public String getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public Object getToolCalls() {
        return toolCalls;
    }

    public Object getToolResponses() {
        return toolResponses;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
