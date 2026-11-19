# US6 编辑与搜索验证

2026-11-19 01:13:13 +08:00，当前 checkout；独立 PostgreSQL17 Testcontainers，无真实模型或外部服务请求。

- 唯一中文、纯空格、换行、缩进匹配替换及空新文本通过；空/零/多/重叠匹配均拒绝且原文保持。
- 两个协作编辑者同时替换同一旧文本，恰好一个提交；SHA-256 检出同大小同时间戳正文变化，保留外部新内容。
- 临时写入/force/提交故障原文不变，无临时残留；原 read/write/list 原子提交和授权测试回归通过。
- grep 字面匹配、1起行号、相对路径排序；glob 的 */?/** 使用动态规划避免模式回溯。扫描≤1000、结果≤200、每文件≤1MiB；字符预算保持合法JSON。
- 非UTF8、二进制、超大、越界文件与目录软链接分别安全跳过；空结果和不完整状态有区分。越界内容/真实路径哨兵未出现。
- 追加逐目录沙箱测试先红灯：再次禁止子目录后仍读到内容；修复枚举前沙箱检查后绿灯。静态检查发现getFileName空值路径，已处理，未放宽规则。
- 应用级经真实Manager/PostgreSQL验证三个工具的原callId/source、下轮回填、未授权/非法参数/begin失败零动作、完成审计未确认不重放、取消与租约失效零访问。

重放：

```bash
mvn -B -ntp -pl AgentOS-boot -am -Dtest=EditFileContractTest,FileSearchContractTest,FileToolsContractTest,FileWriteAtomicityTest,FileAccessSandboxTest,FileSearchAcceptanceIntegrationTest,FileToolsAcceptanceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

结果 PASS，10个reactor质量门均通过，耗时33.720s。日志安全索引：`/tmp/agentos-us6-verify.log`。仅本阶段定向验证，最终完整verify另执行。

部署需显式授权读写根，编辑需双权限。管理员稳定目录结构、所有写方共用规范目标哈希sidecar锁；不承诺抵御非协作写入的最后检查竞态。
