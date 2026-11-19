# 005 Foundational 验证记录

时间：2026-11-18，Asia/Shanghai。基础阶段完成，尚未交付任何新增工具故事。

## 行为红灯

- core：`ToolCatalogSnapshotTest,ToolExecutionScopeTest`，3项，2失败/1错误，编译成功；可变集合、缺失scope行为触发断言/运行时失败。
- tool：`GovernedScopeContractTest,HttpIoScopeContractTest`，编译成功，schema未拒绝、结构化结果非法及尚未实现的HTTP边界触发失败。
- schema：`ToolDefinitionValidatorTest`，编译成功，不安全schema未拒绝/参数未检查触发失败。
- boot：`ToolSnapshotBeforeTurnIntegrationTest`，独立PostgreSQL17.12和真实Manager运行，resolve实际0而预期1，产生行为红灯。

## 定向绿灯

- core快照/scope：3/3，无跳过。
- schema/治理初版：5/5，无跳过。
- core服务/执行器、工具注册、HTTP定向回归通过；首轮boot暴露JpaToolInvocationRecorder只允许builtin来源。
- 修正存储来源校验后，`ToolSnapshotBeforeTurnIntegrationTest` 1/1，无跳过；四种路径覆盖正常、未授权、整批非法JSON、Manager限次跳过；真实HTTP动作分别1/0/0/1，工具审计均保存java:fixture及原callId。
- 后续增强：resolve时检查已有轮次数，确认在startTurn前；scope共享窗口、非合作委托、目录发布失败保留旧快照、合法JSON截断均纳入阶段回归。

## 质量门与修复

- Setup时完整verify：550项，0失败/0错误/0跳过，独立PostgreSQL。
- 基础阶段完整verify第一次在tool的SpotBugs停止：公开方法锁定this。改为私有publicationLock，保留检查规则。
- 第二次完整verify通过core/tool/storage等检查，但boot的旧GET回归出现实际请求2次：立即续租与本地服务无活动数据库连接断言并发，连接关闭触发GET重试。移除发送后重复的立即续租，仍在发送前、每次等待超时、接收正文、返回后检查。
- 第一轮定向verify已通过core/tool的测试及所有静态规则；boot剩余旧夹具schema使用`{}`，不满足005 object输入要求。只将夹具改为`{"type":"object"}`，保留原超时/租约/审计断言。
- 最终定向verify：PASS（2026-11-18 23:20:52 +08:00，51.194秒），`ReActApplicationTest` 7、`ReActDeadlineIntegrationTest` 8、`ToolSnapshotBeforeTurnIntegrationTest` 1、`ArchitectureRulesTest` 14，共30项，0失败/错误/跳过，10个reactor项目全部质量检查成功。
- 命令：`mvn -B -ntp -pl AgentOS-boot -am -Dtest=ReActApplicationTest,ReActDeadlineIntegrationTest,ToolSnapshotBeforeTurnIntegrationTest,ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false verify`。
- 此前core快照/scope、服务/执行器、tool全部契约、HTTP持续正文20次采样及静态检查通过；最后变更仅移除HTTP发送后重复的立即检查和修订旧测试fixture，最终boot已覆盖真实Manager/HTTP/PostgreSQL回归。
- 阶段结论：PASS，可进入US1。最终无测试筛选的完整verify仍由T129执行，不将本次定向verify称为完整005验收。

不修改历史迁移、不引入H2/SQLite、不读取.env、不发真实模型/外部工具请求。存储来源调整属于真实来源审计的必要范围，已同步T014路径。
