# US2 文件工具阶段演示

验证时间：2026-11-18 23:56:29 Asia/Shanghai；状态：PASS（macOS/JDK21、本地临时文件系统与隔离 PostgreSQL）。

## 重放与配置

```bash
mvn -B -ntp -pl AgentOS-boot -am -Dtest=FileToolsContractTest,FileWriteAtomicityTest,FileAccessSandboxTest,FileToolsAcceptanceIntegrationTest,ToolPluginRegistrationTest,ToolPluginPolicyTest,PluginGovernanceContractTest,PluginToolAcceptanceIntegrationTest,ToolAutoConfigurationTest,ArchitectureRulesTest,ReActDeadlineIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

10 个 reactor 项目及格式、Checkstyle、SpotBugs、架构检查全部成功。单元/应用证据分别在 FileToolsContractTest、FileWriteAtomicityTest、FileAccessSandboxTest、FileToolsAcceptanceIntegrationTest，原始日志 `/tmp/agentos-005-us2-verify.log`。
红灯：`/tmp/agentos-005-us2-red.log` 与 `/tmp/agentos-005-us2-boot-red.log`；后者因未注册文件工具而在创建轮次前拒绝。

读写允许根默认均为空；工作根仅用于相对路径解析。部署者显式配置 `agentos.tools.file.read-allowed-paths` / `write-allowed-paths` 后，还需在 Agent `tools` 声明 read_file/write_file/list_dir。
三工具使用 builtin 来源、30s/10000字符默认策略，write_file 非幂等。参数schema隐藏上下文，未知字段拒绝。

## 实际证据

- 创建和显式覆盖成功，磁盘正文可读回；不显式覆盖的碰撞保持原文。缺父目录拒绝且不建目录。
- 只写权限可覆盖而不能读；相同字符串前缀目录、相对/绝对穿越、越界最终软链接拒绝；指向授权目标的入口软链接可读。
- 1MiB边界读写通过，原始字节超限（含多字节中文、读取期间增长）拒绝；非法UTF-8、目录正文读取拒绝。
- 列表只列直接子项，排序、200条上限、独立扫描上限和字符上限生效；不显示锁根与临时文件。
- 临时文件权限0600，完整写入并force后发布。本机真实硬链接创建与原子覆盖均成功；模拟不支持硬链接/原子move时安全失败，无回退。
- 临时准备、写入、force、复核、发布阶段注入失败均保持原文且移除临时名。父目录身份/目标版本变化拒绝。提交后清理失败仍返回committed=true和CLEANUP_UNCONFIRMED，发布计数1。
- 独立协调器对同规范路径串行，稳定sidecar文件释放后仍保留；外部持锁等待受原45ms预算限制，后续正常取得锁。
- 提交前取消或租约失效均保持原文。应用级在审计begin成功后注入取消/数据库租约丢失，原工具审计attempts=0、原callId/source不变、无文件、无下一次模型请求。
- 应用级三次同工具调用受2次测试限值约束：磁盘仅前两个文件，第三行limit_skipped/attempts=0；下一次模型包含三个原ID。
- 正常创建/读取/列表经真实官方Manager与PostgreSQL闭环；未授权/参数错误/begin失败不写文件。complete失败只写一次，数据库unconfirmed且轮次FAILED/UNCONFIRMED。

## 保证边界

本阶段兑现用户确认的可信单机协作写入：部署者保持目录结构稳定，所有写方共用锁根与规范路径SHA-256加`.lock`协议。锁是协作锁，原子替换不提供版本CAS，不承诺抵御任意非协作进程最后检查之后的改名或写入竞态。

文件编辑/搜索、shell、MCP及其他故事尚按后续任务推进；本阶段不代表005最终质量门或全部工具验收完成。
