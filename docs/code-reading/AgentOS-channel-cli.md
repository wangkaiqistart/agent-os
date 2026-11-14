# AgentOS-channel-cli 代码导读

当前模块只有模块说明，真正的 `CliChannel` 类还没有加入。

设计目标是：

```mermaid
flowchart LR
    I[stdin 输入] --> C[CliChannel]
    C --> A[AgentService.process]
    A --> O[stdout 输出]
    C --> Q[/quit 退出]
```

CLI Channel 只负责输入输出适配，不负责模型推理。CLI、REST 和定时任务最终应调用同一个 `AgentService`。

