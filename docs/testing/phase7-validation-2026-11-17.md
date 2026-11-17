# Phase 7 修复与验证记录（2026-11-17）

验证范围是最近出现的启动回收、输入后起步未确认、第二轮审计未确认问题，以及相邻的工具审计与失败恢复边界。保留原阶段任务记录，不将本次回归测试当作 Phase 8/9 全部实现。

## 修复内容与失败证据

| 问题 | 实现前的失败证据 | 修复 |
|---|---|---|
| 启动扫描全历史使用同一个一秒事务 | 真PostgreSQL慢写入超时；后续会话失败回滚前面结果；正常历史也执行工具UPDATE | 定向筛选、按会话独立提交；启动独立预算，锁内重查owner和数据库时间 |
| 正常起步错误套用一秒上限，查证只有250ms | 1.1秒写入使模型调用前FAILED；350ms查证SQL超时 | 普通存储默认5秒并扣同一整轮预算；连接获取也有界；清理仍共用最后一秒 |
| 模型审计往返过多 | 每次SQL/提交等待60～65ms时连续审计失败 | 合并超时设置；正常直接INSERT，异常后按write_id和完整载荷查证 |
| 工具意图审计也有多次冗余往返 | 50ms逐次等待时begin失败，真实工具不能开始 | begin/skipped直接INSERT；一条SQL内先锁会话、再查父轮次及当前时间；异常后完整查证 |

审计单次250ms上限、最多三次审计尝试和200/500ms退避未扩大。未知提交不重放模型或工具，查证发现载荷冲突立即失败。没有修改constitution，没有删除或清理用户数据库。

## 独立环境验证

自动化数据库测试使用 `pgvector/pgvector:pg17` Testcontainers；模型/天气HTTP使用本机替身。往返延迟由测试JDBC包装器注入，SQL、事务、锁、提交和回读仍在真实PostgreSQL执行；该测试不代表测量了实际远程网络的RTT。

| 验证场景 | 结果与证据 |
|---|---|
| 模型连续审计 | 60/65/60ms逐次往返等待，三行独立write_id，全部确认 |
| 工具意图、完成、次数跳过 | 50ms逐次等待，意图/完成复用同一行，跳过独立行，全部确认 |
| 超过审计预算 | 100ms逐次等待仍有界失败、UNCONFIRMED、无插入重放 |
| 提交后丢响应 | 按原键完整载荷查证，仅插入一次；冲突载荷立即拒绝 |
| 等锁期间截止时间到达 | 真实会话FOR UPDATE竞争，取得锁后拒绝已过期父轮次 |
| 十轮对话 | 十轮均SUCCEEDED+CONFIRMED、同会话、每轮两条消息及一条模型审计；后续请求含上一轮历史 |
| 审计失败后恢复 | 首轮FAILED+UNCONFIRMED且不重发模型；故障解除后同会话下一轮成功、审计确认、租约释放 |
| 回收与迟到写入 | 有效owner不回收；失效轮次/孤儿工具收敛；迟到finish不覆盖回收状态、不清新owner |
| 超时与连接池 | 起步不足预算原子回滚；查证/收尾不扩大hardStop；连接池耗尽按操作预算返回，归还后无迟到续租 |
| 外呼与事务 | 模型及工具HTTP等待不占连接，审计失败不会重发外部调用，日志不输出合成密钥/Prompt/响应体 |

`mvn -B -ntp verify` 于 **2026-11-17 01:03:12 +08:00** 完成，耗时 **1:59**：**427 tests / 0 failures / 0 errors / 0 skipped**。core58、provider220、tool25、storage45、web11、channel-cli3、CLI31、boot34。Spotless、Checkstyle、SpotBugs/FindSecBugs、Enforcer和ArchUnit通过；`python3 scripts/check_db_test_reports.py` 确认数据库测试实际执行通过。Boot JAR已重新打包。

诊断日志保存在 `/tmp/agentos-deep-validation-red.log`、`/tmp/agentos-deep-validation-targeted.log`、`/tmp/agentos-deep-validation-verify.log`。可长期复验的证据是对应测试源码，本文件只记录本次结果。

## 真实配置验收

显式使用 `llm` Spring profile，通过现有CLI及应用配置加载密钥，不输出配置值。使用独立测试身份 `phase7-01d4f63c0420`，不归档、删除或复用用户原会话。只进行普通文本对话，不对真实数据库注入超时/锁表/断网等故障。

首次进程三轮通过，耗时 **22.888秒**：收到暗号、正确复述上一轮暗号、输出结束标记。三个独立turnId、同一sessionId、三条模型审计全部CONFIRMED，没有持久化未确认。汇总 `/tmp/agentos-live-chat-summary.json`，日志 `/tmp/agentos-phase7-01d4f63c0420.jsonl`。

随后重启同一测试身份的CLI进程，再发送两条消息。新输入没有包含原暗号，模型仍正确恢复“银杏427”，证明会话历史能跨进程从持久化存储恢复；第二轮结束标记也正确。重启验收耗时 **17.212秒**，累计 **5轮 / 1会话 / 5条CONFIRMED模型审计**，write_id均不同，真实验收日志ERROR数为0；两个进程均正常退出，均记录连接池Shutdown completed。汇总 `/tmp/agentos-live-chat-restart-summary.json`。在本次样本中，原先第二轮模型审计未确认的问题未再出现。

此结论覆盖本次输入、数据量、配置与网络状态；审计250ms硬上限仍存在，不能承诺任意网络延迟下都成功。真实工具外网/天气Demo未在本次真实配置验收中执行，工具链路与故障路径通过上述独立环境测试验证。

## 后续实测修正

用户后续日志再次暴露远程COMMIT及查证超时。本文保留原验收结果作为历史样本，当前审计预算与修复以[数据库安全修复记录](database-safety-2026-11-17.md)及quickstart第13.9节为准：默认2秒、可配置1ms～5秒，deadline后一秒仍共享；不interrupt JDBC工作线程，提交未知仍只查证。新全仓461项测试全部通过，修复版真实四轮及重启验收通过。
