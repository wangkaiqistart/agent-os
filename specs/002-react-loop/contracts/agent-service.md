# Contract: AgentService 与轮次

**Owner**: `AgentOS-core`（`com.agentos.core.turn`） | **Consumers**: CLI；后续 REST、定时入口

类型见 [../data-model.md](../data-model.md)。统一时间预算见 [configuration.md](configuration.md)。

## AgentService.process(ProcessRequest[, TurnSink]) → TurnResult

`ProcessRequest(channel, userId, agentName, message, trigger)`只承载输入数据，不含回调。提供process(request)与process(request,sink)两个重载：单参数调用委托到process(request,TurnSink.NOOP)，双参数要求sink非空；两者共用唯一执行链路。调用方通过sink.streaming()选择同步或流式，CLI用单参数入口。处理顺序：

1. 校验非空消息、Agent 目录/frontmatter、必填模型、工具名单和 provider。失败抛安全的 `AgentOSException`，不建轮次、不发模型请求。工具目录经 core 的 `ToolCatalog` 端口访问，由 tool 模块实现，不违反依赖倒置。
2. 准备会话键，不在core拆开数据库事务；startTurn中读取已有会话时不更新 `last_active_at`，避免忙请求产生写入。
3. 预生成turnId和本轮TurnExecutionBudget（在起步事务前建立，避免把起步耗时排除在计时之外），调用TurnStore.startTurn，在一个短事务内找到/创建活跃会话、回收失效轮次、取得租约、建 `RUNNING` 轮次并写用户消息（迭代0）。租约忙返回 `SESSION_BUSY`，整个事务不写轮次或消息。租约期限至少为本轮 deadline 加30秒，详见 research R8。
4. 使用同一TurnExecutionBudget显式传入循环与工具；在 `TurnBudgetRegistry` 按 turnId 登记同一预算，仅供按关联ID写审计的记录器取得它，不使用 ThreadLocal。注册和移除由 `AgentService` 成对负责。
5. 由 `TurnRunner` 在虚拟线程运行循环，调用线程有界等到 `deadline + 1s`。deadline 后只允许收尾，不再发出模型或工具请求。
6. 从原子startTurn确认提交之后进入try/finally；事务内失败整体回滚。提交结果未知的查询分支见下表，不重放startTurn；之后在共享hardStop内完成在途调用最终审计、finishAndRelease、MDC与预算登记清理。正常超时且审计和收尾均确认时为TIMED_OUT + CONFIRMED，不因“超时”自动变为UNCONFIRMED。失败不得遮盖原始错误。

### startTurn提交结果未知

按预生成turnId在同一预算内查询，不为查询重新分配整轮时长：

| 查证结果 | 行为 |
|----------|------|
| 确认已提交、轮次RUNNING、owner/租约和本地预算均有效 | 使用已存在的会话/轮次继续，不重复建轮次或用户消息；进入同一个finally收尾 |
| 确认已提交，但本地deadline已到、已取消或租约失效 | 不调用模型；分别按TIMED_OUT/INTERRUPTED进入有界收尾 |
| 确认已提交，轮次已经终态 | 使用数据库确认的终态，按finishAndRelease的已收敛规则检查收尾，不重新运行循环 |
| 确认原事务已结束，且成功查询证明未提交 | 抛安全的AgentOSException(INTERNAL_ERROR)，说明轮次启动失败；不返回一个伪造的数据库轮次终态，不发模型请求 |
| 仍无法确认（含原事务可能仍在提交、只查到暂时无行） | 返回本地FAILED、原turnId、UNCONFIRMED，说明轮次是否建立尚未确认；不重放、不调用模型；恢复后按租约失效规则收敛 |

“暂时查询不到turnId”不能单独证明未提交。原事务及查询同样要受数据库I/O和本地hardStop约束。

普通起步/续租/查证/正常收尾的操作时限为 `agentos.turn.storage-timeout`（默认5秒），同时取当前轮次剩余预算的较小值。起步与续租扣执行预算；查证与收尾可使用同一hardStop的剩余时间，deadline后最多仍是共享一秒。连接获取、SQL/行锁、socket和提交都须有界；不能把审计的独立尝试上限（默认2秒）或deadline后一秒清理窗口套到正常起步事务上。起步异常只按原turnId查证，不重放；日志仅记录阶段、turnId、异常类型、耗时和确认状态，不打印原始异常文本/SQL参数/凭证。

开始前的参数/租约错误以及已确认未提交的起步故障抛安全异常；已开始轮次或起步是否提交仍未知时，以TurnResult返回。结果带turnId/status/text/iterations/persistenceStatus。CONFIRMED仅表示必要审计与数据库终态、工具收尾、本owner租约清理均已确认，不表示任务成功；UNCONFIRMED表示至少一项仍未确认。数据库已有确认终态时不得返回一个与其相反的“已确认”本地状态。

### finishAndRelease的条件更新未命中

- 先在同一剩余清理预算内查询旧turnId及本owner租约。旧轮次已被新owner回收为INTERRUPTED、没有遗留in_progress工具记录，且旧owner不再持有租约时，视为已收敛，不是数据库故障；使用INTERRUPTED及数据库已存的结果返回CONFIRMED（前提是本轮必要审计也已确认）。
- 已有其他数据库终态时不覆盖，以该终态为准；仅清理该旧轮次自己的遗留工具记录和仍匹配的旧owner租约，不触碰新owner。清理全部确认后才能返回CONFIRMED。
- 必要审计、查询或清理任何一项无法确认时返回UNCONFIRMED；0行更新本身不能直接推导数据库不可用。

数据库持续不可用时返回安全失败说明、ERROR日志及turnId，不返回成功。不得把缺失审计伪装成已写入，不为了写审计重发外部调用；恢复后依赖租约失效与回收使数据库状态最终收敛，不承诺恢复已经丢失的模型用量。运行时无额外持久化补偿队列。

## 终态对照

| TurnStatus | 什么时候 | 给使用者的内容 |
|------------|---------|----------------|
| SUCCEEDED | 无工具调用且文本非空，或工具全部直接返回 | 最终回答 |
| FAILED | Provider最终失败、空回答、工具调用ID协议错误、审计重试耗尽 | 安全说明；工具执行后写入失败注明操作可能已经执行 |
| TIMED_OUT | 本轮deadline到达 | 本轮处理超时 |
| MAX_ITERATIONS | 调用次数达到上限且仍需继续 | 明确说明，附安全的最后助手文本（如果有） |
| INTERRUPTED | 租约丢失、取消、进程关闭；数据库回收孤儿轮次 | 本轮被中断 |

`LIMIT_EXCEEDED` 仅保留作后续 THROW 模式，默认 RETURN_ERROR_RESPONSE 不产生它。`persistenceStatus` 不改变数据库轮次状态枚举，只说明本次返回是否确认持久化。

## ReActLoop：每次迭代

1. iteration从1开始；`TurnGuard.checkpoint` 检查deadline、取消信号和owner并续租，失效立即停止。
2. 通过 `ToolCatalog` 取得本Agent名单内的治理callbacks。调用 `ProviderService.call/stream`，传当前完整历史、同一个deadline、`CallCorrelation(sessionId,turnId,i)`。Provider请求的 toolContext 只含 TurnContext；**此时尚无工具调用ID账本**。
3. Provider自己在每次尝试返回后调用记录器，循环不重复写模型审计。记录器失败直接结束本轮，不能被当成模型瞬时故障重发。
4. 模型返回后再次检查deadline、取消和租约；没有工具调用时，空文本FAILED，否则追加助手消息并成功结束。
5. 有工具调用：`ToolExecutor` 在整批执行前校验每个ID非空、同一响应内不重复。违反时整批零执行、轮次FAILED，给出固定协议错误，不生成假ID。
6. 建 `ToolCallLedger`，内部键由core常量定义（TurnContext、ledger、budget）；重建交给Manager的Prompt，包含当前累计历史、同一批治理callbacks及内部上下文。只有ToolExecutor调用注入的Manager。
7. Manager正常返回才补写账本剩余ID的 `limit_skipped`；致命中止时剩余请求不是超限。保存本迭代新增助手/工具消息，用完整conversationHistory继续；全部returnDirect时收口。
8. 达到max_iterations不额外调用模型“总结”，以MAX_ITERATIONS结束。

Manager中途致命异常的消息保存规则：工具审计按实际执行情况保留；本次未正常返回的助手/工具消息不追加到 `session_messages`，不拼造半条工具消息。此前已完成迭代的消息保持原样。租约丢失后不得追加任何消息。消息追加在storage短事务内校验当前owner、租约未过期、deadline未到及轮次仍为RUNNING，避免检查与写入之间被接管。

## PromptBuilder

第一次请求按顺序：AGENT.md正文→三个Bootstrap→固定数据安全说明→当地日期（同轮固定）组成SystemMessage；其后为历史；最后是当前UserMessage。记忆和Skill位置预留，本story不加载它们；工具schema只作请求选项。

历史先按完整轮次读取，再同时满足轮数和token预算，从最旧整轮丢弃。当前RUNNING轮次排除；有效租约持有的其他RUNNING轮次不进入历史；接管前先回收失效轮次，因此历史不会把陈旧RUNNING当成功。

SUCCEEDED还原完整消息；其他终态只保留用户消息加一条脱敏的“上一轮处理未完成：<原因>”。历史为0轮或0token时不带历史。正token预算先按90%作为有效估算预算（floor），留10%估算余量；只计历史，不截系统指令与当前用户。单轮历史放不下则整轮丢弃，绝不拆工具请求和结果。

## 输出端、日志与恢复

- process(request,sink)通过sink.streaming()选择Provider调用方式；增量按序转交，工具事件按调用ID和响应顺序，不重放delta。过程回调异常/超时在业务终态确认前视为调用方取消，停止继续推进并收尾，sink标记失效后不再调用。
- complete/error由AgentService在必要持久化处理结束后唯一决定；sink可用且仍有hardStop剩余时间才尝试，SUCCEEDED选complete、其他结果选error，二者互斥，最多尝试一次。过程回调尚未退出时禁止并发重入sink；无预算或已失效时跳过通知并写安全日志，TurnResult仍按时返回。
- 终结回调抛异常或超时，只记录通知失败，不改写已确定的业务status/persistenceStatus、不再次调用onError。其等待/取消也包含在同一hardStop内；详细线程和取消边界见configuration，不以回调任意阻塞为由延长整轮。
- MDC的sessionId/turnId/agent/iteration从TurnContext写入，虚拟线程重新写，finally移除；业务不读MDC。
- `TurnReaper` 在chat启动时执行一次；每次取得过期会话租约时也在同一事务中回收它的旧轮次。回收RUNNING的条件是**没有匹配owner的有效租约**，不要求deadline已经过期；活跃有效租约不回收。
- 启动回收只查询失效RUNNING、终态孤儿工具或终态残留自身租约所在的会话ID，再逐会话独立事务恢复；拿锁后按数据库时间重新检查。单会话内父轮次、工具和自身租约原子收敛；后续会话失败不回滚已完成会话。启动总预算30秒，候选查询及每个会话事务最多5秒并扣总预算；包含连接获取的外层等待有界，超时或失败不得宣称全部恢复完成。此启动预算独立于单轮deadline后的固定一秒清理窗口。
- 额外扫描终态轮次遗留的in_progress工具记录，改interrupted，不改变父轮次终态，不清新owner的租约。正常finally也尽力做同样的工具收尾，重复执行无副作用。
- `--new` 仅在空闲时归档；旧owner失效但旧轮次未清理时，先按上述规则收尾再归档，不删除旧消息和审计。
