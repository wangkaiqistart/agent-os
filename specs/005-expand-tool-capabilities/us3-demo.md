# US3 本地命令与受控进程演示

验证时间：2026-11-19 00:16:00 Asia/Shanghai。状态：PASS（本地进程、脚本化模型、隔离 PostgreSQL）。

## 重放

```bash
mvn -B -ntp -pl AgentOS-boot -am -Dtest=ShellToolsContractTest,ManagedProcessTest,ShellToolsAcceptanceIntegrationTest,ToolAutoConfigurationTest,ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

该组共27项独立测试。首次全组质量门通过后，补充进程调用上限测试并运行应用/架构16项定向verify，10个reactor均成功；日志 `/tmp/agentos-005-us3-verify.log` 与 `/tmp/agentos-005-us3-final.log`。
行为红灯 `/tmp/agentos-005-us3-red.log`（6项可编译行为失败）和 `/tmp/agentos-005-us3-boot-red.log`（未注册shell）。

## 实际行为证据

- 默认程序/cwd/环境继承名单为空。真实程序精确授权，cwd按真实目录组件匹配；相对程序名/PATH选择、未授权cwd、NUL、129参数和超过64KiB参数均在启动前拒绝。
- 含空格、分号、管道、通配符、命令替换的argv按原样进入测试程序，未产生额外文件。非零退出7作为结果返回，无自动重跑。
- 清空ProcessBuilder环境后，只继承指定LANG并解析显式业务变量；实际子进程无HOME或未声明宿主秘密。缺失环境引用返回固定UNCONFIGURED，不暴露变量名/值。
- stdout与stderr同时洪泛；合计原始保留字节恰为65536，超额继续排空，进程正常退出；UTF-8使用替换解码，结构化结果字符裁剪后仍为合法JSON。
- 无法确认终止的注入场景在夹具兜底清理前断言进程仍活、cleanupConfirmed=false、scope清理未确认，未伪称成功。
- 应用级经真实Manager与PostgreSQL：授权动作计数文件为单个x；未授权、参数错、begin失败、审计begin后的取消/租约失效均无文件；complete失败文件仍为单个x，未重放。原source=builtin/callId/attempts均检查，非致命结果回填下一次模型。
- 同一批3次shell调用、测试限值2：动作文件恰为xx，第三次limit_skipped/attempts=0，三个原callId均反馈模型。

## 进程树清理样本

样本来自真实Java父/子进程，700ms工具预算；使用工具自身collect的超时/取消路径，在外层夹具close前确认二者退出。`elapsedMs`从子进程就绪后的工具等待开始计时；取消样本包含取消后的清理等待，超时样本包含剩余预算等待与清理。原始40条样本见 `fixtures/process-cleanup-samples.json`。

| 触发 | 样本 | p95毫秒 | 最大毫秒 | 根/子进程退出 |
|---|---:|---:|---:|---:|
| timeout | 20 | 641.904 | 643.020 | 20/20 |
| cancel | 20 | 5.621 | 5.630 | 20/20 |

质量门保留原规则；FindSecBugs对独立argv的ProcessBuilder启动报通用COMMAND_INJECTION，按CLAUDE约定仅在该方法添加附授权/测试依据的局部误报注解，并用与质量门相同版本的provided注解依赖。不添加排除文件或放宽阈值。

白名单允许的程序拥有宿主权限，授权解释器后可以自行进行文件/网络操作；不提供容器隔离，也不保证识别已脱离/重新托管的后代。以上样本仅证明受控父/子进程的实际结果。

US4及后续故事仍待实施，本报告不是005最终验收。
