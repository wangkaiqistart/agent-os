# Quickstart：005 验收与演示指南

本指南提供 005 已交付命令、测试类及 fixtures 的重放路径。实际运行结果见 acceptance-results.md、
各 us*-demo.md 及 demo-results.md；真实模型评估与外部依赖未运行项明确标为 NOT_RUN。
设计依据见 [plan.md](./plan.md)、[data-model.md](./data-model.md) 和 [contracts/](./contracts/)。

## 1. 前置条件和运行边界

- JDK 21、Maven、Docker；数据库自动化使用独立 `pgvector/pgvector:pg17` Testcontainers。
- Linux/macOS 的临时工作区；完整文件写入验收需硬链接、同目录原子 move、FileLock。
  不支持时记环境限制/失败，不把跳过算通过；不通过降低原子性来适配。
- 文件权限/命令/域名默认均为空；仅对临时目录、本地受控进程和 localhost 服务显式授权。
  目录和锁根由部署者保持稳定，所有写入方遵守相同协作协议。
- 默认测试使用脚本化模型、本地三 transport MCP/HTTP/搜索替身，不连接共享库/真实模型。
  不读取个人凭证文件，不发送消息或通知；不以 Memory/Notify/定时为前置条件。
- 配置按 [configuration.md](./contracts/configuration.md) 使用环境占位符；禁止输出环境值或 `.env`。

## 2. 基线与目标自动验证

从仓库根执行，先保存既有改动：

```bash
git status --short --branch
java -version
mvn -version
docker info --format '{{.ServerVersion}}'
```

新增测试类的目标名称如下；实施时建立同名可发现测试，不允许“指定测试不存在”被当成功：

```bash
mvn -B -ntp -pl AgentOS-tool -am -Dtest=ToolPluginRegistrationTest,FileToolsContractTest,ShellToolsContractTest,McpLifecycleContractTest,HttpPostContractTest,WebSearchContractTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -pl AgentOS-cli -am -Dtest=AskUserInputOwnershipTest,ToolListCommandTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -pl AgentOS-web -am -Dtest=ToolCatalogAuthenticationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`failIfNoSpecifiedTests=false` 只为 reactor 依赖模块无对应类；目标模块必须生成预期测试报告且 tests>0。
应用级 PostgreSQL 闭环由最终完整质量门执行，定向单元测试不能替代它。

## 3. 隔离演示 fixtures 的交付契约

实施需交付 `specs/005-expand-tool-capabilities/fixtures/full_tool_acceptance.py`，
仅使用标准库或仓库已有依赖。它创建临时工作区、最小部署配置、本地服务/进程、脚本化模型和测试数据；
接受 `--jar` 与 `--output`，只运行隔离目标，不读取 `.env`，退出时有界清理自己启动的进程。
MCP fixtures 应完整实现本期需要的 initialize/list/call 三种传输路径，不只是伪造回调返回值。
验收记录输出到 output 指定目录；安全配置/脚本可入库，真实凭证与原始敏感输出不入库。

实施完成后可运行：

```bash
mvn -B -ntp verify
python3 scripts/check_db_test_reports.py --tools
python3 specs/005-expand-tool-capabilities/fixtures/full_tool_acceptance.py --jar AgentOS-boot/target/AgentOS.jar --output target/005-tool-acceptance
```

fixtures 不代替 PostgreSQL 集成测试；它汇总 CLI/服务外部行为和动作次数。
Java 集成测试负责假模型→AgentService→真实 Manager→治理→外部替身→下一次模型请求及审计持久化。
报告必须证明 Surefire 当前运行与 XML 时间对应，不混入旧真实模型报告。

## 4. 按故事验收

| 故事 | 操作与故障 | 必须核对的外部证据 |
|---|---|---|
| US1 插件 | 加 @Tool 查询插件、非幂等副作用 callback；分别授权/未授权；注入重名、无描述、非法 schema、begin 失败 | 注册只一次、未知本地名建轮次前失败；拒绝/审计未确认真实执行数=0；正确 source/callId、returnDirect 保留 |
| US2 文件 | 读 UTF-8；创建、默认碰撞、显式覆盖、列目录；同名前缀/上级路径/软链接；超大/编码/临时写失败 | 读回一致；拒绝原文件不变；授权软链接可读、越界不可读；提交前无半成品；不支持原子能力安全失败 |
| US3 命令 | argv 特殊字符、非零退出、输出洪泛；未授权程序/cwd；注入宿主秘密 env 与显式业务 env；超时和取消各20次 | 额外命令数=0、拒绝启动数=0；秘密不可见、允许业务值可见；输出≤64KiB、无堵塞；进程树退出确认或明确不确定 |
| US4 MCP | stdio/sse/streamable-http 各发现/调用/关闭；两个服务同名；永不响应来源；断开/恢复/缺失与新增工具 | 三种均有真实协议请求数；本地就绪增量≤1s；旧代次不换绑；失联新轮次定义0、调用0；新增未授权0；环境/metadata 哨兵零泄漏 |
| US5 POST | 文本/JSON；超限、未授权、无效 JSON；302/307；已收请求后断线/慢正文；完成审计失败 | 前置拒绝0请求；每原目标≤1、下一跳0；请求体一致；未知状态明确可能生效；不回显错误/超大正文 |
| US6 编辑/搜索 | 唯一匹配、空/零/多/重叠匹配；纯空格/换行/缩进不 trim；提交前协作变更；二进制/非UTF8/超大/链接/扫描截断 | 纯空白旧文本唯一匹配可替换；只改一处；拒绝原文不变；路径/行号有序；空、截断、不完整分明；越界内容0 |
| US7 提问 | 可交互输入；无人值守20次；EOF、BUSY、持续无换行超长输入、超时/取消后迟到回答 | 答案回原调用；无人值守每次<1s；超限立即 TOO_LONG，保留量≤上限+1及固定缓冲；取消可结束等待；迟到误交付0；reader数不增长 |
| US8 搜索 | Brave 协议替身有/空/超条数；未配置/未授权/失败/超大/慢响应；在头与错误里植入测试秘密 | 参数/凭证头正确；返回≤10；前置拒绝0请求；六类状态分明；不请求结果页面；各输出渠道秘密0 |
| US9 查询 | tool list 与认证 HTTP 绑定同一快照；全局/Agent；本机及显式远程监听；对/错/缺/未配置token | 字段集合一致；未发现来源仍显示；未认证目录/Agent读取=0；查询新增模型/工具/会话/轮次=0；无token/URL/env泄漏 |

所有故事还需覆盖错误输入、调用限次、工具超时、整轮超时、取消、租约丢失、审计未确认和敏感信息。
实际 callback 结果须按原 tool_call_id 回填下一次模型请求，不能只单测工具方法。
审计失败的检查区分 begin 未确认零执行与 complete 未确认可能已执行，禁止重放。

## 5. 目录 CLI 与只读服务演示

以下命令已在005实现；`/tmp/agentos-005` 的配置与工作区由 fixtures 建立。

```bash
java -jar AgentOS-boot/target/AgentOS.jar --config /tmp/agentos-005/application.yaml tool list --workspace /tmp/agentos-005/workspace --json
java -jar AgentOS-boot/target/AgentOS.jar --config /tmp/agentos-005/application.yaml tool list --workspace /tmp/agentos-005/workspace --agent tool-demo --json
java -jar AgentOS-boot/target/AgentOS.jar --config /tmp/agentos-005/application.yaml tool serve --workspace /tmp/agentos-005/workspace
```

服务须配置 query.enabled、环境引用 token；默认回环。无 token 的拒绝可用：

```bash
curl --silent --show-error --include http://127.0.0.1:8080/api/v1/tools
```

预期 401、ApiResponse code=UNAUTHORIZED、data=null，且读取目录次数为0。
正确/错误 token 由验收 harness 在内存中构造请求头，不在命令参数或日志展示真实 token。
未配置 token 的 serve 非零退出且不监听；其他本地能力仍可用。
远程监听只在显式测试配置下开放，使用部署者加密链路；对本机和远程分别跑四类 token 情况。

## 6. MCP 故障恢复与隐私验收顺序

1. 启动 healthy-a、healthy-b、never-respond；记录无坏来源/有坏来源的本地 readiness 时间。
2. 声明 healthy-a 的公开工具，记录 generation/version 与模型定义；调用时核对审计真实 source。
3. 断开 healthy-a，等来源状态失效；开始新轮次，断言工具定义0，查询保留来源，模型伪造请求动作0。
4. 恢复来源并更改清单，等待下一次计划重连；新轮次只恢复原授权存在项，新增未声明项不可执行。
5. 旧轮次回调不能切新客户端；恢复后缺失声明工具在新轮次创建前报错。
6. 单来源超时取消只使该来源代次失效，healthy-b 和本地工具照常成功；不重放未知调用。
7. STDIO 环境哨兵、ToolContext 内部对象哨兵、远端错误/URL/stderr 秘密哨兵均不出现在不允许渠道。
8. 关闭应用，后台重连停止，受控服务连接/进程在共享窗口内确认退出；不能用测试外层 finally 杀进程冒充客户端清理成功。
9. SSE 分别返回跨 scheme、host、有效端口的 message endpoint，其中包含通用白名单内的另一服务；
   所有跨源请求数和凭证接收数为 0，同源相对端点正常通过，HTTP/HTTPS 默认端口等价比较有测试。

## 7. 时间与完整性门禁

慢正文、MCP 远程等待、持续命令输出、交互等待、持续搜索响应各20次。
测试先独立预热再创建每个样本预算，不能在样本内重置 deadline。
记录逐样本实际开始动作/正文、取消/退出确认和耗时；p95 用20个排序值的第19个。
每类 p95≤配置预算+1000ms；shell 超时/取消另各20次确认受控进程树退出。
取消关闭必须在外层服务清理前被观测，不能用自然结束或测试 shutdown 作为通过证据。
清理和审计共享1s；耗尽则明确未确认，不为通过验收加新窗口。

保留004的授权/审计/重试/预算/脱敏/恢复回归；新增case纳入当前 Surefire及数据库证据检查。
实施需扩展 `scripts/check_db_test_reports.py --tools` 校验005关键 PostgreSQL 集成类确实执行、tests>0/0跳过。
本地 Docker 不可用时可报告数据库 NOT_RUN，但不得宣称完整门禁/005验收通过；CI=true 时不得跳过。

## 8. 三个可重放汇总 Demo

- 天气：授权本地天气HTTP替身，http_get读取，结果返回当前CLI；不推送通知。
- 科技：read_file读取授权公共Skill参考，MCP/搜索替身取资讯，组摘要返回当前入口；不依赖长期记忆。
- GitHub：使用授权MCP/Java业务插件获取受控仓库信息并组日报；不让通用shell冒充被隔离的业务脚本。

每个Demo要保存模型可见集合、实际动作数、同callId审计、下一轮工具回填和最终输出的安全证据。
脚本化模型Demo通过不代表真实模型兼容通过。

另交付 `AgentOS-boot/src/test/java/com/agentos/boot/llm/DemoEvaluationIntegrationTest.java`，
在 `-Pllm` 且设置测试 `AGENTOS_LLM_API_KEY` 时运行；默认 verify 排除，不依赖真实搜索或外部 MCP。
三个 Demo 使用真实模型处理本地受控资料、真实 Manager/PostgreSQL，并核对预期工具调用和审计。
天气/GitHub 用 `FactCheckingEvaluator`，科技用 `RelevancyEvaluator`，EvaluationRequest 上下文取实际
工具结果、回答取最终输出。各评估 3 次，至少 2 次 `isPass()` 才 PASS，不用 `getScore()` 判断。
优先使用可用的 deepseek Provider 评估，否则 primary 并记录自评限制；评估 ChatClient 不传 tools。
在 `demo-results.md` 记录每个 Demo 的三次结果、评估 Provider、安全资料关联和 PASS/FAIL/NOT_RUN。
缺凭证或未执行必须 NOT_RUN；不能宣布三个 Demo 的评估验收全部通过。

## 9. 可选真实联调

现有真实模型入口为 `mvn -B -ntp verify -Pllm`，需显式提供测试模型环境变量并开启相应测试。
真实搜索/外部MCP另由 `external-tools` Maven profile 与同名 JUnit tag 控制，
默认排除 `llm,external-tools`，不得把新外部请求漏入普通 verify。

```bash
mvn -B -ntp verify -Pllm
mvn -B -ntp verify -Pexternal-tools
```

第二条命令只选择external-tools，并要求
`AGENTOS_EXTERNAL_TOOL_TESTS=true` 以及该服务的显式测试凭证/地址；缺少依赖报告NOT_RUN。
真实模型、搜索、各外部MCP分别记录，不把其中一项成功推断为全部成功。

## 10. 结果交付

报告每项 PASS/FAIL/NOT_RUN、开始/结束时间（Asia/Shanghai）、命令/环境、测试计数、
工作区/服务来源安全标识、动作次数、审计确认、清理确认、20次样本及p95。
总览按SC-001–014逐项给证据位置；没有运行记录不能写PASS。
收尾先执行只读 analyze，再在独立修复任务中处理已报告问题并保存报告，随后只读复核；
分析技能内不编辑文件，最终在实施任务中归档复核及质量门结果。
完成前运行最终 `mvn -B -ntp verify`、数据库报告检查与 `git diff --check`。
格式修复只在需要时执行 `mvn spotless:apply`；不放宽质量门，不自动提交/推送/合并/发布。

## 11. 当前实现的重放入口

```bash
python3 specs/005-expand-tool-capabilities/fixtures/full_tool_acceptance.py --jar AgentOS-boot/target/AgentOS.jar --output /tmp/agentos-005-packaged
python3 specs/005-expand-tool-capabilities/fixtures/demo_summaries.py --output /tmp/agentos-005-demos
python3 -c 'from pathlib import Path; import time; Path("target").mkdir(exist_ok=True); Path("target/verify-started-at").write_text(str(time.time()))'
mvn -B -ntp verify
python3 scripts/check_db_test_reports.py --tools
```

打包harness先在同一交互CLI进程用无工具探测轮次等待MCP来源进入后续快照，再验证工具调用和ask_user回答；不假定首轮远程工具立即可见。
打包harness仅用标准库、java、docker；自行建立并回收独立pgvector PostgreSQL，不读取.env，模型和搜索均为本地协议替身。
JUnit中五类慢动作各20样本写入`AgentOS-boot/target/tool-deadline-samples.json`，不把外层shutdown算工具清理。
真实评估：`mvn -B -ntp -pl AgentOS-boot -am -Pllm -Dtest=DemoEvaluationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`，
需要显式AGENTOS_LLM_BASE_URL/API_KEY/MODEL；可选AGENTOS_DEEPSEEK_BASE_URL/API_KEY/MODEL供独立评估。
外部兼容：`-Pexternal-tools` + AGENTOS_EXTERNAL_TOOL_TESTS=true；Brave用AGENTOS_EXTERNAL_BRAVE_API_KEY。
MCP对每种传输分别配置AGENTOS_EXTERNAL_MCP_STDIO_CONFIG/TOOL/ARGUMENTS、AGENTOS_EXTERNAL_MCP_SSE_CONFIG/TOOL/ARGUMENTS、
AGENTOS_EXTERNAL_MCP_HTTP_CONFIG/TOOL/ARGUMENTS。CONFIG为仅含一个server的部署YAML，敏感值环境引用；
TOOL为精确公开名，ARGUMENTS为业务JSON。只指定可安全执行的测试工具。每种依赖独立启用/报告，未配置NOT_RUN。
