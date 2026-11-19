# Contract：内置工具

所有参数均为 object，未知参数拒绝；仅列出的业务字段进入模型 schema，ToolContext/scope/交互端口
不进入 schema。所有工具遵守 [tool-catalog.md](./tool-catalog.md) 的治理、原调用 ID、预算和脱敏。
以下为目标契约；既有 http_get 的正常/跳转/截止时间行为保持 004 契约。

## 参数及结果

| 工具 | 输入字段（? 为可选） | 正常结果 | 幂等默认 |
|---|---|---|---|
| read_file | path:string | `{path,content,truncated}` | true |
| write_file | path:string, content:string, overwrite?:boolean=false | `{path,bytesWritten,committed:true}` | false |
| list_dir | path:string, limit?:integer=200 | `{entries:[{path,name,type}],truncated,incomplete,skipped}` | true |
| shell | executable:string, args?:string[]=[], cwd?:string=工作根 | `{exitCode,stdout,stderr,truncated,cleanupConfirmed}` | false |
| http_post | url:string, body:string, content_type?:string="application/json" | `{status,body,truncated,completion:"CONFIRMED"}` | false |
| edit_file | path:string, old_text:string, new_text:string | `{path,replacements:1,bytesWritten,committed:true}` | false |
| grep | path:string, text:string, limit?:integer=200 | `{matches:[{path,line,content}],truncated,incomplete,skipped}` | true |
| glob | path:string, pattern:string, limit?:integer=200 | `{paths:[string],truncated,incomplete,skipped}` | true |
| ask_user | question:string | `{answer:string}` | false |
| web_search | query:string, limit?:integer=10 | `{items:[{title,url,snippet}],truncated,complete}` | true |

输入字符串不接受 null；路径/问题/查询/grep text/pattern 不可空白，正文、新文本可空。
old_text 只要求长度大于 0，允许纯空格、换行或缩进，不得 trim 或以 isBlank 拒绝。
limit 下限 1，上限是部署限值且不超过规格默认值。executable 是真实绝对路径，不接受任意命令串；
args ≤128 项、UTF-8 总计≤64 KiB，无 NUL；由部署者收紧。正文字符截断不会绕过原始字节上限。
结果 JSON 先按项和内容字段满足治理字符预算，保留合法结构；无法容纳最小结果时配置校验失败。
工具 schema、返回结构和配置应有契约测试，避免文档字段与实际 callback 定义漂移。

## 文件语义

- 相对 path 相对部署工作根，绝对 path 走同样授权；默认无读写权限。
  原始输入不直接成为错误中的绝对目标。返回 path 是请求的安全相对表示/授权根别名下路径，
  不返回软链接最终越界地址。
- 路径检查按真实组件；读取软链接仅当最终目标在读范围可用。写入锁定最终真实目标，
  提交前复核原请求解析到同一目标及父目录身份。新目标必须有真实已授权父目录。
- read_file 只普通 UTF-8 文本，最多 1 MiB；严格解码，含 NUL 的内容按 UNSUPPORTED_TYPE 拒绝，空文件和正常空白文本合法。编辑和 grep 复用同一文本判定；过大报错，不返回部分字节冒充完整文本。
  通过字节上限后仍可因 10000 字符结果上限缩短 content，truncated=true。
- write_file 默认仅创建；已存在须 overwrite=true 才可覆盖。只写权限不授予读取旧正文权限。
  不隐式建父目录；先准备完整临时文件，再校验预算/授权/版本后发布。
- 默认创建用同目录硬链接发布，存在冲突零覆盖；覆盖/编辑用原子 move。
  不支持硬链接/原子 move 或无法确认授权时安全失败，禁止回退直接目标写入。
- edit_file 同时需读写权限，old_text 长度大于 0（允许纯空白、不 trim）且恰好出现一次（按重叠起点也计数），
  文件内容和结果均≤1 MiB；提交前版本/摘要不变，否则原文保持。
- 写操作先取得 canonical path 进程锁及稳定 sidecar FileLock。锁协议及平台保证见
  [data-model.md](../data-model.md)；部署者保持目录结构稳定，外部写方协作。
  任意非协作外部修改最后检查之后的竞态不在承诺范围。
- 成功发布后临时名删除失败不得伪称未提交；返回安全清理警告并保留真实审计状态，不重新发布。

## 列表和搜索

- list_dir 仅直接子项；type 为 file/directory/symlink/other，超限条目不返回内容。
- grep 字面、区分大小写；行号 1 起，无任意正则；每行返回一次匹配，内容受限。
- glob 针对 path 下的相对路径，使用 `/` 分隔；支持 `*`、`?`、`**`，
  禁绝对模式、`..`、NUL、字符类/大括号扩展；pattern≤1000 字符。
- 搜索不递归目录软链接，文件软链接最终目标仍须读授权。目录项/文件读取前分别 checkpoint。
  返回按相对路径、行号稳定排序；扫描上限 1000 项，包含目录枚举而不是只计成功读取文件。
- 字节上限 1 MiB/文件、结果 200 项、扫描上限和时间独立控制。
  达到结果上限为 truncated；因扫描上限或跳过内容为 incomplete，二者可同时为 true。
  枚举上限时只对已扫描子集排序，不承诺未扫描部分的全局顺序。
- skipped 仅计安全分类 `unsupported_encoding/too_large/unreadable/denied/unsupported_type`，
  不附越界真实路径。零命中且 incomplete=true 不能表述为完整无结果。

## 命令语义

精确授权真实 executable 和 cwd；模型不能通过 PATH、env、cwd 软链接替换程序。
argv 原样传递，管道/重定向/命令替换字符只是字符，不隐含执行解释器。
部署者显式允许解释器后，它拥有宿主机权限；白名单不限制程序内部的文件/网络行为。
清空环境后仅基础变量允许名单和该程序显式配置值；没有模型 env 参数。
stdout/stderr 同时排空，共享保留 64 KiB，超额丢弃且标记截断；非零 exitCode 是命令结果，
不自动重跑。超时/取消/租约失效进入可识别进程树清理，终止未确认明确报告。
工具自身 timeout 可反馈模型，整轮 timeout/租约丢失仍结束轮次，不能通过 JSON 成功回包掩盖。

## POST 与搜索

POST 只支持 UTF-8 text/plain 或 application/json；JSON 文本先解析。正文≤1 MiB，响应≤1 MiB。
网络动作前检查原目标、域名、协议、userinfo、正文和 budget；禁自定义认证头/内部上下文。
禁止跳转和重发；2xx 为传输层成功，不推断业务正文表示成功。
非 2xx/超大/网络错误不附远端正文或原始异常。send 后故障、取消或完成审计未知，
说明“提交可能已生效”，原目标最多一次、跳转下一跳零次，以替身计数验收。
整轮 deadline、取消或租约失效不转为可继续结果；最终 TurnResult、有效入口的终结错误回调及本轮新确认的
final_text 均保留该提示。未发送的前置拒绝不登记提交；正常成功在完成审计确认后解除本次登记。
提交结果不确定与持久化确认分别表达，不为警告重放请求或延长清理/审计窗口。

web_search 通过部署的 Brave 实现，配置缺失为 UNCONFIGURED；不得返回桩内容当真实搜索。
查询≤1000 字符，返回≤10 条，响应≤1 MiB，服务地址需获准；参数不含密钥/endpoint。
链接/摘要是数据，不自动访问命中页面或扩大白名单。完整空列表是成功；
结果项/字符裁剪为 truncated；服务失败/拒绝/未配置/超大响应为明确错误，不伪造空成功。

## 交互

ask_user 通过当前轮次 InteractionPort，不依赖 trigger 或 System.in。
UNSUPPORTED 立即返回，EOF/BUSY/TOO_LONG 明确失败，问题和回答各≤4000 字符。
等待受 scope deadline、取消、checkpoint 约束；不创建新轮次或延长租约预算。
CLI 单一 broker 绑定 requestId/generation；取消后迟到行丢弃，不能成为后续聊天/提问的输入。
未释放的底层读取期间新请求 BUSY；不无限积累阻塞线程。
回答逐字符或固定大小块增量读取，最多保留 max-answer-chars+1 个字符；第一个超限字符即返回
TOO_LONG，禁止先 readLine() 完整缓存后判断。同一 reader 用固定大小缓冲丢弃余行至换行或 EOF，
不缓存超额内容；期间超时/取消只结束工具等待，底层读/丢弃仍属旧 generation，broker 保持 BUSY。
须以大于上限且持续无换行的输入验证保留量不增长、等待可取消、迟到内容零串用。

## 失败分类和验证

面向模型的错误只暴露安全类别与是否可能已执行，例如 INVALID_ARGUMENT、DENIED、UNAVAILABLE、
UNCONFIGURED、TOO_LARGE、UNSUPPORTED、TIMEOUT、CONFLICT、EXECUTION_UNCERTAIN。
这些是工具安全错误语义，不要求将每个类别新增为 REST ErrorCode。
审计状态仍用现有数据库枚举；审计、租约和整轮超时按原致命规则处理。
每个工具必须验证正常、未授权、错误输入、上限、超时/取消、审计与下一迭代结果回填。
