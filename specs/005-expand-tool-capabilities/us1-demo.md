# US1 Java 插件接入与验收

验证时间：2026-11-18 23:36:36 Asia/Shanghai。状态：PASS（本地插件与隔离 PostgreSQL）。

## 重放

```bash
mvn -B -ntp -pl AgentOS-boot -am -Dtest=ToolPluginRegistrationTest,ToolPluginPolicyTest,PluginGovernanceContractTest,PluginToolAcceptanceIntegrationTest,ToolAutoConfigurationTest,ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

本次 26 项测试全部通过、零跳过；10 个 reactor 项目及格式、Checkstyle、SpotBugs、ArchUnit 全部成功。
红灯日志：`/tmp/agentos-005-us1-red.log`（发现与策略行为）；`/tmp/agentos-005-us1-boot-red.log`（插件未装配）。
绿灯及质量门日志：`/tmp/agentos-005-us1-verify.log`。

## 接入步骤

1. 声明普通 Spring Bean，业务方法添加官方 `@Tool`；按需添加方法级 `com.agentos.tool.annotation.ToolPolicy`。
2. 或声明 `ToolCallback` Bean；需要显式治理参数时声明 `ToolRegistration(beanId, callback, policy)` Bean。
3. Agent 的 `tools` 精确声明公开工具名。仅成为 Bean 不授予 Agent 权限。

缺省策略为非幂等、30 秒、10000 字符；注解覆盖经测试实际生效。官方 schema、ToolContext 隐参、returnDirect 均保留。
代理 advice 仅在执行时调用，注册发现零业务动作。同对象别名去重，不同对象重名、保留前缀、非法 schema 均拒绝，旧目录保持。

## 应用闭环证据

`PluginToolAcceptanceIntegrationTest` 用本地脚本化模型、注入的真实官方 Manager 和 Testcontainers `pgvector/pgvector:pg17`，逐场景检查磁盘外业务计数及数据库原行：

| 场景/原 callId | 实际动作 | 审计/轮次 | 下一次模型 |
|---|---:|---|---|
| lookup | 2 次尝试 | 单行，source=java:queryPlugin，attempts=2，轮次成功 | 含原 ID 与 fixture-lookup |
| mutate | 1 | 单行，source=java:mutationPlugin，attempts=1 | 含原 ID |
| denied | 0 | 单行，原 source，attempts=0 | 含原 ID 的拒绝结果 |
| begin-failure | 0 | begin 未确认，轮次 FAILED/UNCONFIRMED | 不继续模型 |
| complete-failure | 1，不重放 | 单行 unconfirmed，轮次 FAILED/UNCONFIRMED | 不继续模型 |

幂等插件明确已开始的瞬时失败只执行一次；40ms 插件预算能中断合作式等待；结果不超注解上限。
插件接入无需新增循环分支，本阶段未修改 Foundation 之后的 ReActLoop。

US1 完成只代表 Java 插件可接入；文件、进程、MCP 及其他工具仍按后续任务实施，不视为已交付。
