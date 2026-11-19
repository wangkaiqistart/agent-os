# 005 最终实施与验收记录

2026-11-19，Asia/Shanghai。005 的9个故事与135项实施任务已交付；Phase 16 收敛任务 T135 已完成并验证。
真实模型、三个官方评估器及外部 Brave/MCP 联调为 **NOT_RUN**，不宣称模型评估或外部兼容验收通过。

## Phase 16 最新验证

本阶段完成 T135。最终质量门从 2026-11-19 13:51:06 +08:00 开始，13:57:24 +08:00 完成。

| 检查 | 结果与证据 |
|---|---|
| `mvn -B -ntp verify` | PASS，699项测试、134类，0失败/错误/跳过；10/10 reactor成功，耗时6分17秒 |
| T135 测试先行及定向回归 | 本地HTTP＋真实Manager＋PostgreSQL先复现deadline后缺少提示；修复后44项定向回归通过，完整门禁包含新增的请求正文送达后租约丢失回归 |
| POST 致命终止 | PASS，deadline/cancel/lease三类；每类正文实际送达1次、同批后续动作0次、模型1次，返回值/有效onError/final_text均有“提交可能已生效”；原callId/source/attempts关联一致 |
| 数据库报告 | PASS，使用最终verify实际起点核对新报告，Plugin/MCP独立原样重放补齐XML容器标记；004的2类/7方法及005的13类，独立PostgreSQL17/pgvector；检查器离线9项通过 |
| 最终JAR标准库harness | PASS，12次授权成功、12次拒绝且零动作，24条审计全部收尾；JAR SHA-256见verification-summary.json |
| 时限和进程清理 | PASS，五类各20次均满足原1800ms上限；进程超时/取消各20次，父子退出且工具清理确认 |
| 静态检查及证据schema | PASS，Spotless/Checkstyle/SpotBugs/FindSecBugs/ArchUnit随verify通过；打包及五类证据共6份经Draft202012Validator＋FormatChecker验证 |
| 只读analyze与保护检查 | 62/62 FR/SC有任务映射，135个任务唯一、显式依赖有效，无未解决漂移；135/135完成；spec/plan/constitution/checklist哈希未变，checklist仍16/16，无扩展hooks |

POST 通过显式 scope/budget 的独立 Submission 登记发送边界。校验拒绝不会登记；只有成功响应、
结果治理、清理和完成审计全部确认才解除本次登记，后续另一提交成功不会消除较早提交的不确定性。
终结文本由该显式状态追加固定安全警告，正常返回、致命终止和持久化收尾失败均保留警告。
超时、取消和租约失效仍保留原终止状态与优先级，完成审计/轮次持久化确认独立表达。
仍RUNNING的失效租约轮次只使用固定安全警告收口，不接收迟到成功答案；已有数据库终态仍优先，
不清除接管者租约。没有增加Manager、ThreadLocal、重放或时间预算，原hard stop及共享清理/审计窗口不变。

回归还覆盖正常成功、确定未发送的前置拒绝、完成审计未确认、收尾写入失败、较早未知与较晚成功的组合、
已恢复数据库终态优先及T134清理未确认终止。三类实际POST终止样本见
`fixtures/evidence/phase16/post-terminal-samples.json`，相关35条回归见`t135-regression.json`。

前两轮完整verify曾失败：13:05:10的http_get7得到INTERRUPTED而非TIMED_OUT；13:14:05的shell12得到FAILED。
期间原样独立重放100个时限样本于13:08:33通过，不能替代全量质量门，失败日志保留。
重复失败日志显示同一scope多个I/O线程的续租事务争抢sessions行锁。新增三个确定性回归先红，
分别复现并发续租引发租约失败、排队检查没有等待边界，以及排队后取消仍等待原deadline。scope现用受原预算约束的锁串行检查点，
获取锁及续租返回后再次检查截止时间，排队期间持续观察取消；不增加预算、不改时限断言或减样本。验证结果见最终质量门及归档。
第三轮全量于13:23:24在CLI输入夹具失败：原短生命周期写线程退出后，PipedReader将尚未关闭的管道报告为
Write end dead，broker据此得到EOF，下一行断言收到null。独立JDK探针确认该行为。夹具改为单个持续存活写线程，
用latch协调超长行余部与下一行；原TOO_LONG/BUSY/保留上限/下一行及预算断言不变，11项定向回归通过。
该修订只涉及测试输入时序，未修改输入broker的产品代码。

第四轮全量于13:31:28在shell6失败：工作线程关闭scope触发取消和等待自己的Future，
CancellationException遮盖原deadline，落成清理未确认FAILED。新增回归先复现同一异常身份丢失；
治理线程现先登记所有权再启动，自身关闭只清理其他资源，委托退出前整体清理仍未确认。
外部关闭保持取消和实际退出确认，继续阻止不合作委托后的同批动作。首次定向验证另发现外部确认退出后
工作线程登记未解除，已修复并保留失败日志；原普通工具超时、T134清理终止、共享窗口与样本断言未改。

第五轮全量在Shell进程退出检查失败：轮次结果已返回，工作线程发起的清理仍在途。
新增latch回归先复现治理方在工具超时后提前返回；修复为在原剩余清理窗口内等待工作线程在途清理和退出，
随后才完成审计并发布终结结果。首次定向验证暴露治理取消仍会中断在途清理，
已用同一所有权锁协调取消与清理开始，避免中断已进入清理的工作线程。没有增加窗口；原进程存活、1800ms上限和全部样本断言不变。

首次数据库检查因Plugin/MCP的本轮XML缺少容器启动镜像标记而失败；两类本轮测试均成功，且有PostgreSQL版本标记。
独立原样重放这两类补齐所需运行证据后重新检查，不修改检查器、测试源码或门禁规则。

| 类型 | 样本 | 本轮p95毫秒 | 预算+窗口毫秒 |
|---|---:|---:|---:|
| http_get | 20 | 822.885 | 1800 |
| mcp_fixture__echo | 20 | 931.166 | 1800 |
| shell | 20 | 809.742 | 1800 |
| ask_user | 20 | 825.759 | 1800 |
| web_search | 20 | 822.133 | 1800 |

本阶段证据位于 `fixtures/evidence/phase16/`；只归档合成计数、布尔检查及安全时限数据，原始日志仅存本机临时目录。
完整verify日志中的合成秘密哨兵零命中。真实模型、官方评估器、外部Brave及外部MCP仍为 **NOT_RUN**。

日志索引：

- `/tmp/agentos-005-phase16-red.log`：新增真实HTTP/PostgreSQL行为回归先红。
- `/tmp/agentos-005-phase16-targeted.log`：44项定向回归通过。
- `/tmp/agentos-005-phase16-verify-first.log`、`/tmp/agentos-005-phase16-verify-second.log`、`/tmp/agentos-005-phase16-deadline-replay.log`、`/tmp/agentos-005-phase16-verify.log`：前两轮失败、独立重放及最终完整质量门。
- `/tmp/agentos-005-phase16-checkpoints-{red,green}.log`、`/tmp/agentos-005-phase16-checkpoint-cancel-{red,green}.log`：并发检查点、排队取消边界先红后绿。
- `/tmp/agentos-005-phase16-verify-third.log`、`/tmp/agentos-005-phase16-pipe-probe.log`、`/tmp/agentos-005-phase16-cli-fixture-green.log`：CLI夹具失败、JDK探针及修订后定向验证。
- `/tmp/agentos-005-phase16-verify-fourth.log`、`/tmp/agentos-005-phase16-self-close-{red,first,green}.log`：工作线程自行关闭的失败、回归和修复。
- `/tmp/agentos-005-phase16-verify-fifth.log`、`/tmp/agentos-005-phase16-cleanup-handoff-{red,first,green}.log`：在途清理提前返回的失败、回归与修复。
- `/tmp/agentos-005-phase16-db-final.log`、`/tmp/agentos-005-phase16-checker-tests.log`：新窗口数据库证据与检查器回归。
- `/tmp/agentos-005-phase16-db-first.log`、`/tmp/agentos-005-phase16-db-replay.log`：首次数据库检查失败与两类原样重放。
- `/tmp/agentos-005-phase16-packaged.log`：最终JAR验收。

未提交、推送、合并或发布，保留既有未提交改动。


## Phase 15 历史验证

本阶段完成 T134。完整质量门从 2026-11-19 11:44:30 +08:00 开始，11:51:04 完成；
后续定向回放与打包验收单独记录，不将历史失败改写为通过。

| 检查 | 结果与证据 |
|---|---|
| `mvn -B -ntp verify` | PASS，689项测试、133类，0失败/错误/跳过；10/10 reactor成功，6分32秒 |
| T134 定向回归 | PASS，5类共32项测试；其中8条相关case归档至 `fixtures/evidence/phase15/t134-regression.json`，含真实PostgreSQL应用回归 |
| 数据库报告 `--tools` | PASS，004的2类/7关键方法、005的13类；Plugin/MCP分别独立重放补齐XML容器启动标记，检查器规则未改 |
| DB报告检查器离线回归 | PASS，9项 |
| 最终JAR标准库harness | PASS，12次授权成功、12次拒绝且零动作，24条审计全部收尾，环境隔离检查通过 |
| 时限与进程清理 | PASS，五类各20次，p95均≤1800ms；进程超时/取消各20次，父子进程均退出且清理确认 |
| 后续工具定向回放 | PASS，ManagedProcessTest、ShellToolsContractTest、McpFailureBoundaryTest共16项；用于复核完整质量门之后被覆盖的报告中出现的启动/等待超时 |
| 证据schema与静态检查 | PASS，6份证据经Draft202012Validator及FormatChecker校验；格式/Checkstyle/SpotBugs/架构检查随完整verify通过，规则未放宽 |
| 漂移复核及完成状态 | 只读analyze未发现本轮新增冲突，62/62 FR/SC有映射；134/134任务完成；constitution/checklist哈希未变，checklist 16/16，无扩展hooks |

T134 增加 core 的显式 `ToolCleanupUnconfirmedException`。治理层在 scope 返回清理未确认时，
先在原窗口内完成原调用审计，再抛出终止信号；即使此前是普通业务错误，也不能反馈成可继续的工具结果。
官方异常处理器透传该信号，轮次服务返回包含“清理未确认、本轮已停止、禁止重放”的失败说明。
资源执行是否确定与持久化是否确认分别表达：审计与轮次提交可确认时不伪报数据库写失败；收尾失败仍为UNCONFIRMED。
已有审计失败、租约丢失和整轮超时保持原终止原因，普通业务错误在清理成功时仍可反馈模型。
没有新增Manager、备用执行入口、ThreadLocal或时间预算，turn hard stop及资源清理/审计共用1秒窗口不变。

测试先行：治理层3个case先复现“Manager不抛终止信号”；真实PostgreSQL应用测试先复现错误地SUCCEEDED；
核心轮次与异常处理器也分别先红后绿。最终测试覆盖清理返回false、并发关闭仍在途、伴随业务错误、
官方方法适配器包装及既有致命异常优先级。应用回归通过真实注入Manager验证：原调用仅执行一次，
第二个文件写入零动作、模型仅请求一次、无部分TOOL历史落库、会话租约已释放，原callId/source/attempts保持一致。

| 类型 | 样本 | 本轮p95毫秒 | 预算+窗口毫秒 |
|---|---:|---:|---:|
| http_get | 20 | 818.482 | 1800 |
| mcp_fixture__echo | 20 | 926.108 | 1800 |
| shell | 20 | 806.155 | 1800 |
| ask_user | 20 | 826.455 | 1800 |
| web_search | 20 | 827.513 | 1800 |

首次JAR验收在CLI `communicate` 的原75秒等待上限处超时，安全失败证据保留于 `fixtures/evidence/phase15/attempt1/`。
相同JAR、未修改验收限制的独立重放于12:23:31 +08:00通过，首次超时原因未确认，不归因于代码或环境。
完整质量门之后，3类工具报告出现启动/等待超时（共1失败、4错误）；独立重放16项于12:26:54通过，
未修改源码或预算，最终133类报告均为成功。归档过程中还出现过一次shell临时文件空间错误，随后原操作重试成功。
以上异常保留为验证过程事实，不替代完整质量门和最终独立回放的实际结果。

本轮证据位于 `fixtures/evidence/phase15/`，验证摘要包含JAR SHA-256、100次时限原始样本、40次进程清理样本、
8条T134相关case、schema结果及受保护文件哈希。合成秘密日志扫描零命中。
真实模型、三个官方评估器、外部Brave及外部MCP仍为 **NOT_RUN**，本地替身通过不代表真实外部兼容验收。

本轮日志索引（原始日志仅保留在本机临时目录）：

- `/tmp/agentos-005-phase15-{governed,application,terminal,processor}-red.log`：先红证据。
- `/tmp/agentos-005-phase15-targeted-green.log`：32项定向回归。
- `/tmp/agentos-005-phase15-verify.log`：完整质量门。
- `/tmp/agentos-005-phase15-{db-refresh,mcp-db-refresh,db-final,checker-tests}.log`：数据库证据与检查器。
- `/tmp/agentos-005-phase15-packaged.log`、`packaged-final.log`：首次失败与最终打包验收。
- `/tmp/agentos-005-phase15-final-tool-replay.log`：3类16项工具边界独立回放。

未提交、推送、合并或发布，保留既有未提交改动。

## Phase 14 历史验证

本阶段完成 T132/T133；以下以 11:17:16 开始的新验证窗口为准，保留 Phase 13 及更早证据。

| 检查 | 结果与证据 |
|---|---|
| `mvn -B -ntp verify` | PASS，684项测试、133类，0失败/错误/跳过；10/10 reactor成功，6分24秒，11:23:41 +08:00完成 |
| 数据库报告 `--tools` | PASS，004的2类/7关键方法、005的13类；真实PostgreSQL17/pgvector；Plugin/MCP两类分别重放补齐XML镜像启动标记，检查器规则未改 |
| DB报告检查器离线回归 | PASS，9项 |
| 最终JAR标准库harness | PASS，12次授权成功、12次拒绝且零动作，24条审计全部收尾；授权组网络3次、拒绝组0次；环境隔离检查通过 |
| 时限与进程清理 | PASS，五类各20次，p95均≤1800ms；进程超时/取消各20次，父子进程均退出且清理确认 |
| 证据schema | PASS，打包证据与五类时限证据均用Draft202012Validator及FormatChecker验证 |
| 架构/格式/安全静态检查 | PASS，随完整verify运行，未放宽规则；本轮合成秘密日志扫描零命中 |
| 完成状态 | 133/133任务完成且编号唯一；constitution/checklist哈希未变，requirements checklist 16/16，无扩展hooks |

T132 在 MCP env/header 解析时登记完整解析值和每个实际读取的引用分量，登记先于插件发现，
标准执行模式和最小目录模式使用各自的共享 Redactor。只查找配置声明的引用，不枚举宿主环境；
缺失引用保持来源级安全失败，其他来源仍可用，不向查询DTO或日志添加秘密字段。
新增回归覆盖 Bearer header、带前后缀的显式env、多引用、远端回显原始token，
以及未执行MCP时先行文件/Java插件回显。首次输入、begin/complete入参、真实PostgreSQL审计、
事件、历史、模型回填、描述和目录均验证脱敏。配置/隐私/目录共13项定向测试先红后绿。

T133 将 scope 清理状态保持为未确认，直到首个关闭者完成所有资源并发布终态；
并发或递归关闭保守返回未确认，不重新分配窗口，不等待另一个关闭者而形成循环等待。
失败/中断后的未确认不会被重复关闭覆盖；成功终态可被后续关闭观察。
latch控制回归覆盖最终成功/失败、重复/递归关闭、取消/中断、共享绝对deadline，
以及工具发起关闭与治理线程同时收尾时的错误结果和完成审计，确认只有一次动作且不重放。
核心scope、治理及进程清理共15项定向测试先红后绿；整轮hard stop及资源/审计共用1秒窗口未变。

| 类型 | 样本 | 本轮p95毫秒 | 预算+窗口毫秒 |
|---|---:|---:|---:|
| http_get | 20 | 814.885 | 1800 |
| mcp_fixture__echo | 20 | 924.441 | 1800 |
| shell | 20 | 807.364 | 1800 |
| ask_user | 20 | 823.027 | 1800 |
| web_search | 20 | 812.623 | 1800 |

本轮安全证据位于 `fixtures/evidence/phase14/`，包含打包结果、100次原始时限样本、
逐类schema证据、40次进程清理样本及验证摘要。默认测试与harness只访问本地合成服务。
真实模型、三个官方评估器及外部Brave/MCP仍为 **NOT_RUN**。

本轮日志索引（原始日志仅存本机临时目录）：

- `/tmp/agentos-005-phase14-mcp-{red,green}.log`：MCP隐私/配置/目录回归。
- `/tmp/agentos-005-phase14-{scope,governed}-red.log`、`scope-green.log`：并发清理先红后绿及进程回归。
- `/tmp/agentos-005-phase14-verify.log`：最终完整质量门。
- `/tmp/agentos-005-phase14-{db-refresh,mcp-db-refresh,db-final,checker-tests}.log`：数据库证据及检查器。
- `/tmp/agentos-005-phase14-packaged.log`：最终JAR验收。

未提交、推送、合并或发布，保留既有未提交改动。

## Phase 13 历史验证

本阶段完成 T130/T131，保留前阶段证据；以下以 10:42 开始的新验证窗口为准。

| 检查 | 结果与证据 |
|---|---|
| `mvn -B -ntp verify` | PASS，676项测试、133类，0失败/错误/跳过；10/10 reactor成功，6分20秒，10:48:25 +08:00完成 |
| 数据库报告 `--tools` | PASS，004的2类/7关键方法、005的13类；真实PostgreSQL17/pgvector；Plugin/MCP两类补跑刷新XML内启动标记，检查器原规则未改 |
| DB报告检查器离线回归 | PASS，9项 |
| 最终JAR标准库harness | PASS，12次授权成功、12次拒绝且零动作，24条审计全部收尾；授权组网络3次、拒绝组0次；环境隔离检查通过 |
| 时限与进程清理 | PASS，五类各20次，p95均≤1800ms；进程超时/取消各20次，父子进程均退出且清理确认 |
| 证据schema | PASS，打包证据与五类时限证据均用Draft202012Validator及FormatChecker验证 |
| 架构/格式/安全静态检查 | PASS，随完整verify运行，未放宽规则 |
| 一致性复核 | 62/62要求保留映射，131个任务编号唯一；修复符合FR-005/006/011、SC-013/014与T116/T129；constitution/checklist哈希未变，无扩展hooks |

T130 将 Shell 显式配置秘密的登记提前到装配阶段，覆盖字面值、引用值与组合值的原始引用部分，
只解析配置指定的引用。首次 Shell 普通argv、先行文件/插件结果及目录描述均脱敏，
begin/complete 审计正常关联；缺失引用仍在调用时安全返回UNCONFIGURED，不启动进程。
隐私/目录/环境定向17项测试先红后绿，真实PostgreSQL验收通过。

T131 在共享有界文本读取中统一拒绝含NUL的内容，read_file/edit_file/grep使用相同判定。
保留严格UTF-8、精确1MiB成功、超限拒绝、空文件及正常空白文本；拒绝编辑时原文件及目录不变。
文件定向29项测试先红后绿，并覆盖真实Manager/PostgreSQL。

完整验证还暴露两个时限问题，均已修复：慢HTTP夹具原先只等待700ms，却依赖额外开销耗尽750ms预算，
现用有界latch保持响应至本轮结束，原预算、模型调用次数及1200ms断言不变；
TurnGuard续租异常路径原先可能把截止时间已到错误归类为租约丢失，现优先复核截止时间并保留包装的deadline异常，
真实提前存储故障仍为LeaseLost。新增2项确定性回归先红后绿，连同核心时限和应用测试共13项通过，随后完整verify通过。
此前全量失败不是已通过证据，也未作为环境偶发问题忽略。

| 类型 | 样本 | 本轮p95毫秒 | 预算+窗口毫秒 |
|---|---:|---:|---:|
| http_get | 20 | 816.3 | 1800 |
| mcp_fixture__echo | 20 | 936.4 | 1800 |
| shell | 20 | 807.5 | 1800 |
| ask_user | 20 | 820.1 | 1800 |
| web_search | 20 | 812.4 | 1800 |

本轮安全证据位于 `fixtures/evidence/convergence/`，包含打包结果、原始时限样本、逐类schema证据、
40次进程清理样本及验证摘要。`deadline-independent.json`为修复前独立重放历史，不替代最终全量样本。
真实模型、三个官方评估器及外部Brave/MCP仍为NOT_RUN；676项默认测试和本地harness不产生这些外部请求。

本轮日志索引（原始日志仅存本机临时目录）：

- `/tmp/agentos-005-convergence-{privacy,input-audit}-red.log`、`privacy-green.log`：隐私红绿。
- `/tmp/agentos-005-convergence-files-{red,green}.log`：文件边界红绿。
- `/tmp/agentos-005-convergence-renewal-{red,green}.log`：续租分类红绿及13项定向回归。
- `/tmp/agentos-005-convergence-react-fixture-green.log`：HTTP夹具7项回归。
- `/tmp/agentos-005-convergence-verify-complete.log`：最终完整质量门。
- `/tmp/agentos-005-convergence-db-refresh.log`、`db-final.log`、`checker-tests.log`：数据库证据与检查器。
- `/tmp/agentos-005-convergence-packaged.log`：最终JAR验收。

## Phase 12 历史验证

| 检查 | 结果与证据 |
|---|---|
| `mvn -B -ntp verify` | PASS，666项测试、134类，0失败/错误/跳过；10/10 reactor成功，5分56秒，02:41:29 +08:00完成 |
| 数据库报告 `--tools` | PASS，当前验证窗口；004的2类/7关键方法、005的13类；真实PostgreSQL17/pgvector，未使用共享库或替代数据库 |
| DB报告检查器离线回归 | PASS，9项，含缺失/跳过/失败/过期/空套件拒绝 |
| 最终JAR标准库harness | PASS，12个允许调用成功、12个拒绝，24条审计全部收尾；允许组网络3次、拒绝组0次；交互ask_user成功 |
| 三个汇总Demo | PASS，天气/科技/仓库，真实Manager/PostgreSQL与本地模型协议；本轮verify重放，脚本汇总当前结果 |
| 证据schema | PASS，Draft202012Validator完整校验打包证据及五类时限证据，date-time格式校验启用 |
| 架构/格式/安全静态检查 | PASS，包含15项ArchUnit、Spotless、Checkstyle、SpotBugs/FindSecBugs及依赖禁令 |
| 只读analyze | 62/62要求均映射，129个任务唯一；无未解决CRITICAL/HIGH，无未映射任务；constitution/checklist未修改 |

全量首轮暴露查询Bean误绑定自定义执行目录，修复并以新增测试先红后绿；保留快照原断言后重跑全量通过。
全量XML有两类缺容器镜像启动日志，单独复跑Plugin/MCP后原数据库检查脚本通过；未放宽检查规则。
打包脚本此前存在首轮远程发现竞态，改为同一CLI进程按真实快照探测来源就绪；最终2次探测后开始正式调用。
探测只用本地脚本化模型、不执行工具，正式调用的两次模型请求与审计计数独立记录。

## 成功标准定位

| 标准 | 默认验收 | 证据 |
|---|---|---|
| SC-001 | PASS | 10新增工具+http_get目录一致，矩阵108场景；各us1–9-demo；packaged-details.json |
| SC-002 | PASS | 授权矩阵、文件/Shell/MCP/POST/搜索集成；拒绝零动作与原文件保持断言 |
| SC-003 | PASS | PluginToolAcceptanceIntegrationTest、插件定义/重名契约；循环无插件分支 |
| SC-004 | PASS | 文件与编辑契约/应用测试；协作锁、原子发布、版本复核、失败零部分写入 |
| SC-005 | PASS | ManagedProcessTest超时/取消各20次，父子均在工具清理中退出；process-cleanup-final.json |
| SC-006 | PASS | 三transport契约、生命周期与应用级真实协议；us4-demo.md |
| SC-007 | PASS | 失联隐藏、来源generation冻结、重连恢复及精确授权；McpLifecycleTest等 |
| SC-008 | PASS | HttpPostAcceptanceIntegrationTest及契约；一次提交、无重定向下一跳，未知状态明确提示 |
| SC-009 | PASS | FileSearchAcceptanceIntegrationTest与搜索契约；空结果/截断/不完整及越界零泄漏 |
| SC-010 | PASS | AskUserAcceptanceIntegrationTest、输入broker契约；无人值守20次、迟到隔离、有界增量输入 |
| SC-011 | PASS | WebSearchAcceptance/Unconfigured集成及Brave本地协议契约；六类结果区分 |
| SC-012 | PASS | 查询认证/CLI/目录模式/同快照集成；认证前零读取、查询零业务新增；us9-demo.md |
| SC-013 | PASS | Privacy/矩阵/打包harness；宿主合成凭证Shell/STDIO不可读，业务值可读且安全出口脱敏，原callId关联 |
| SC-014 | PASS（默认路径） | 五类各20次与004回归；真实依赖和模型评估单独NOT_RUN |

## 五类时限样本

整轮预算800ms，资源清理和审计共用1000ms收尾窗口；每个样本确认动作、审计终态、持久化CONFIRMED和真实清理。
p95取排序后第19个值；外层应用shutdown不能作为清理通过证据。

| 类型 | 样本 | p95毫秒 | 预算+窗口毫秒 | 结果 |
|---|---:|---:|---:|---|
| http_get | 20 | 820.3 | 1800 | PASS |
| mcp_fixture__echo | 20 | 930.0 | 1800 | PASS |
| shell | 20 | 805.2 | 1800 | PASS |
| ask_user | 20 | 821.1 | 1800 | PASS |
| web_search | 20 | 817.3 | 1800 | PASS |

原始样本与逐类schema证据见 `fixtures/evidence/deadline-samples-final.json` 和 `deadline-*.json`。
初始定向样本 `deadline-samples-initial.json` 仅作历史记录；本表取本轮完整verify。

## 外部验证与部署边界

真实模型、FactCheckingEvaluator/RelevancyEvaluator的三次isPass、Brave及外部MCP三transport均NOT_RUN，
原因是未注入显式测试配置；详见 external-results.md、demo-results.md。默认666项测试不产生这些外部请求。
已交付显式llm/external-tools测试入口，官方评估用实际工具资料，每项三次至少两次isPass；缺凭证不算通过。

文件写入范围是可信单机协作协议：部署者保持目录/锁根稳定，全部写方使用同一sidecar锁。
程序白名单不隔离获准程序内部动作；MCP连接授权不代表远端内部隔离；远程查询依赖部署者提供加密链路。

## 安全证据与日志索引

归档仅包含合成夹具的计数、布尔检查、安全资料与时限；不读取.env、不复制原始运行日志或凭证。
最终verify日志中隐私哨兵扫描零命中；隐私测试另覆盖事件/历史/审计/模型回填/查询及远程metadata隔离。

- `/tmp/agentos-005-final-verify.log`：最终全量绿灯；`*-first.log`为已修复首轮失败。
- `/tmp/agentos-005-query-binding-{red,fixed}.log`：查询绑定回归红绿证据。
- `/tmp/agentos-005-shell-red.log`、`/tmp/agentos-005-cross-fixed.log`：Shell隐私修复及100次时限定向验证。
- `/tmp/agentos-005-db-evidence-refresh.log`、`/tmp/agentos-005-db-final.log`：数据库证据复跑与检查。
- `/tmp/agentos-005-packaged-final.log`、`fixtures/evidence/packaged-{details,evidence}.json`：最终JAR验收。
- `/tmp/agentos-005-llm.log`、`/tmp/agentos-005-external.log`：显式外部路径条件跳过，不能作PASS。

最后检查无扩展hooks，`git diff --check`通过。未提交、推送、合并或发布；保留既有未提交改动。
