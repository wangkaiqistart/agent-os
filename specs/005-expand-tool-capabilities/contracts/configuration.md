# Contract：部署配置与权限

以下为 005 已实现的部署配置。沿用现有 `agentos.tools.http` 前缀，
新增键采用 kebab-case。所有相对文件配置路径相对工作根；启动配置路径由 CLI --config 指定。
示例只含占位符，不要求查询模式读取 `.env` 或连接数据库。

## 应用配置

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: false
    chat:
      client:
        tool-calling:
          enabled: false
agentos:
  workspace:
    root: .AgentOS
  tools:
    file:
      read-allowed-paths: []
      write-allowed-paths: []
      lock-root: .tool-locks
      max-file-bytes: 1048576
      max-results: 200
      max-scan-entries: 1000
      timeout: 30s
      max-result-chars: 10000
    shell:
      commands: []
      inherit-environment: []
      max-output-bytes: 65536
      timeout: 30s
      max-result-chars: 10000
    http:
      allowed-domains: []
      max-request-bytes: 1048576
      max-response-bytes: 1048576
      timeout: 30s
      max-result-chars: 10000
      max-redirects: 3 # 只适用既有 GET；POST 永不跳转
    interaction:
      max-question-chars: 4000
      max-answer-chars: 4000
      timeout: 30s
    search:
      provider: brave
      endpoint: https://api.search.brave.com/res/v1/web/search
      api-key: ${AGENTOS_SEARCH_API_KEY:}
      max-query-chars: 1000
      max-results: 10
      max-response-bytes: 1048576
      timeout: 30s
    query:
      enabled: false
      bind-address: 127.0.0.1
      port: 8080
      access-token: ${AGENTOS_TOOL_QUERY_TOKEN:}
```

读写允许根默认空，工作根不隐式授权。示例要启用时部署者显式填入存在的真实目录；
写允许根不存在、锁根不安全/无法建立协作锁、限值非正或超出本契约最大值都安全失败。
锁根由部署者维护，拒绝通过文件工具/搜索修改或披露；临时文件和锁文件从工具列表中排除。
不同实例与外部协作写入方必须使用同一锁根与规范路径算法。

Shell commands 每项定义 `executable`（真实绝对文件）、`allowed-cwds`（目录列表）、`env`（显式映射）。
executable 精确匹配，cwd 按真实目录组件校验。inherit-environment 指定允许读取的基础变量名；
缺省空，禁止自动追加完整宿主环境。env 只解析部署者列出的项，敏感值必须使用环境引用。
示例可显式允许基础 `LANG`/`TMPDIR` 等，但程序不能因 PATH 去选择其他 executable。
模型没有 env/命令授权配置参数。服务器原始 env 不记录、不进入目录查询。

限值可收紧，不能用负数/零解除资源限制。所有工具等待还取本轮剩余 budget 最小值；
重试、分页、重连不能重置已进行调用的预算。MCP 重连只用于未来连接，不重放当前业务调用。
未配置搜索凭证只使 web_search 为 UNCONFIGURED；未配置文件/程序/域名授权使动作默认拒绝。
所有配置凭证值纳入 Redactor 的已知秘密集，新增来源不能只依赖旧 Provider 秘密集。
Shell 显式 env 中可解析的配置值及原始环境引用值在工具装配阶段登记，早于目录投影、事件和首次输入审计；缺失引用不阻断其他工具或目录模式，调用该命令时仍返回安全 UNCONFIGURED。

MCP 配置路径固定为工作根下的 `mcp_servers.yaml`；初始化预算10秒、响应上限1 MiB、
最多发现1000个工具、重连退避1秒倍增至300秒是当前固定安全边界，未提供 `agentos.tools.mcp.*` 属性。
调用策略可在下方服务或工具级 `timeout` / `idempotent` / `max-result-chars` 收紧。
STDIO 基础环境继承名单共用 `agentos.tools.shell.inherit-environment`；服务级仅接受 `env`，
不接受服务级 `inherit-environment`。

## mcp_servers.yaml

```yaml
servers:
  - name: local-demo
    transport: stdio
    command: ${AGENTOS_MCP_DEMO_COMMAND}
    args: []
    cwd: ${AGENTOS_MCP_DEMO_CWD}
    env:
      DEMO_BUSINESS_VALUE: ${AGENTOS_MCP_DEMO_VALUE}
    timeout: 30s
    tools:
      lookup:
        idempotent: true
        timeout: 10s
        max-result-chars: 10000
  - name: news
    transport: sse
    url: ${AGENTOS_MCP_NEWS_BASE_URL}
    sse-endpoint: /sse
    headers:
      Authorization: ${AGENTOS_MCP_NEWS_AUTHORIZATION}
  - name: reports
    transport: streamable-http
    url: ${AGENTOS_MCP_REPORTS_BASE_URL}
    endpoint: /mcp
    headers: {}
```

name 唯一且符合 server-id 规则；不同 transport 字段互斥，不接受模糊 fallback。
stdio 必须命中 shell 的程序/cwd 授权；env 由服务独立声明，不自动借用其他命令业务变量。
remote url 只 HTTP(S)、无 user-info/fragment，端点实际请求同样受 HTTP 白名单校验；
SSE 返回的 message endpoint 不能借首次 URL 授权获得其他域名权限。
HTTP 实际端点还必须与该来源 url 同源（规范化 scheme/host/有效端口）；跨源即使命中通用
allowed-domains 也在发请求及附加 headers 前拒绝，凭证仅附加到该来源同源请求。
本期不提供跨源端点或凭证接收范围配置，不跨来源复用认证头。
headers 由部署者配置且凭证只引用环境，不暴露给模型。未解析引用只报告 server-id/字段名，
该来源不可用，不打印原值、不拖本地能力就绪。配置文件缺失表示无 MCP 来源。
同名项/非法 YAML 须明确报告，不能静默当空文件；使用 SnakeYAML 安全构造器、拒绝重复 key。
运行期配置热重载不在本期；服务重连只重新发现已加载的配置来源。

## 工具目录 HTTP 认证与启动

`AgentOS tool serve` 选择目录模式，由 boot launcher 创建最小 Servlet 上下文。
query.enabled 必须为 true，且 access-token 为非空有效值（16–4096 UTF-8 字节）才装配入口；
否则命令非零安全退出，不影响 chat/tool list/init 等本地能力。
bind-address 默认回环，非回环必须显式填写；端口 1–65535。
未配置 token 时该路径不装配，嵌入其他 HTTP 模式时返回普通 404；不回退匿名。

认证头为 `Authorization: Bearer <token>`；比较使用固定摘要/常量时间比较，限制头长度。
缺失/错误/重复头/格式非法统一 401；认证在读取目录及加载 Agent 之前。
URL 不支持 token/access_token/api_key，出现此类参数时安全拒绝，即使 header 正确也不采纳 URL 值。
新增 UNAUTHORIZED 错误码时补全 GlobalExceptionHandler 映射；Filter 直接输出同一 ApiResponse 信封，
不能依赖 MVC advice 自动捕捉 Servlet Filter 异常。认证失败 data=null、固定消息、不反射输入。
返回 `Cache-Control: no-store`；目录模式无通配 CORS，不沿用 debug WebConfig 的 `/api/**` 放行配置。

远程连接依赖部署者提供 TLS/加密反向代理；示例不把真实 token 填到 URL 或命令参数。
目录模式只读取已经维护的目录，不通过查询按需执行发现/插件/模型；
启动期可后台维护 MCP，但本地查询不等待来源就绪。目录装配不加载 Provider/Embedding/存储执行链，
真实执行装配仍需真实审计端口；不能以查询模式的假 recorder 绕过执行审计。

## 协作写入前提

管理员保证授权目录及锁根结构稳定，所有写方以规范目标哈希取得同一个 sidecar 锁后写入。
提交前核对授权、原路径解析、父目录、文件版本；编辑还比较内容摘要。
不支持硬链接、原子移动、身份确认或协作锁时失败，不降级。
这不阻止不协作外部进程在最后检查后修改路径，不属于容器沙箱承诺。
