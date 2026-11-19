# US7 当前入口提问

2026-11-19 01:29:12 +08:00：PASS，独立PostgreSQL，默认无人值守，无真实外呼。

- AgentService增加显式TurnRuntimeCapabilities重载，旧调用及--message无人值守；能力仅经原循环/执行器的内部上下文传入，不进入模型schema或MCP。
- 交互CLI用CliInputBroker，channel共用TerminalInputBroker（实现位于channel避免反向依赖）。一个固定虚拟reader按generation归属；聊天与提问串行共享。问题先脱敏。
- 输入归属三项测试先行为红灯，后绿灯：聊天/回答/EOF；10万字符无换行输入第4001字符立即TOO_LONG且保留量≤4001；同一reader丢弃余行，新请求BUSY；取消等待后迟到行不交下一请求。最大回答可由请求收紧。
- 应用级提问先出现预期1次、实际0次行为红灯，再实现ask_user；原turnId/callId、下次模型回填、秘密问题/答案脱敏均通过，轮次仅增加1。
- 未授权/错误参数/begin审计失败/取消/租约丢失均不提问，complete未确认不重放。
- 无人值守应用链连续20次均<1s且回填UNSUPPORTED，不读取System.in。
- 旧聊天、core执行/失败/关闭、ReAct及架构回归通过；更新了新重载相关mock签名，保留原断言。修正测试SQL为既有result_text，无迁移。

重放：

```bash
mvn -B -ntp -pl AgentOS-boot -am -Dtest=InteractionPortContractTest,AskUserInputOwnershipTest,AskUserAcceptanceIntegrationTest,CliChannelTest,ChatCommandTest,ToolExecutorTest,AgentServiceTest,AgentServiceFailureTest,ReActApplicationTest,ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

上述回归除最后修订的提问SQL外全部通过；最后重跑AskUserAcceptanceIntegrationTest的verify PASS（2项，0失败/错误/跳过，28.744s，10 reactor质量检查成功）。安全日志索引：/tmp/agentos-us7-verify.log、/tmp/agentos-us7-final.log。最终完整verify另执行。

配置agentos.tools.interaction的max-question-chars/max-answer-chars默认4000，timeout默认30s，只能收紧。ask_user非幂等、不重试；不支持时返回明确错误，不把回答创建成新用户消息。
