package com.agentos.storage.session;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** agent_turns 的 CRUD 代理；轮次主键由应用生成，不使用消息表的自增序号。 */
public interface AgentTurnRepository extends JpaRepository<AgentTurnEntity, UUID> {
    /** 回收操作查询 RUNNING 残留；查询本身不会启动后台任务或自动修改状态。 */
    List<AgentTurnEntity> findByStatus(String status);
}
