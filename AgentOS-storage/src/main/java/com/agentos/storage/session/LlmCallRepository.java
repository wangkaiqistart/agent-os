package com.agentos.storage.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 模型调用审计的 CRUD 代理，Long 对应数据库自增主键。 */
public interface LlmCallRepository extends JpaRepository<LlmCallEntity, Long> {
    /** 以稳定写入标识查询记录；声明此查询并不代表 saveAndFlush 已实现重复写入的幂等成功。 */
    Optional<LlmCallEntity> findByWriteId(UUID writeId);
}
