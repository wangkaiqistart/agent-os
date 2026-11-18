# Contract: HTTP GET Tool

## Input

模型可见参数只有：

```json
{
  "url": "https://approved.example/path"
}
```

`ToolContext`、轮次、租约、预算和调用账本由运行时注入，不进入模型 Schema。

## URL and sandbox rules

- 必须是绝对 URL。
- 只允许 `http` 或 `https`。
- 拒绝 URL user-info。
- 默认域名白名单为空，未列入即拒绝。
- 域名比较不区分大小写。
- 精确域名只匹配自身。
- `*.example.com` 只匹配子域，不匹配 `example.com`。
- IP 地址必须精确列入；不因 localhost 或内网属性自动放行。
- 每次重定向前后都重新执行同一白名单规则。
- 禁止自动跟随；最多 3 次重定向，缺少或无法解析 Location 时安全失败。

## Time and body limits

- 连接、响应头、重定向和正文消费共用本次工具的单调截止时间。
- 工具截止时间取配置 30 秒和整轮剩余预算的较小值。
- 持续收到正文分片不得重置或延长截止时间。
- 正文最多读取 1 MiB；超限立即取消订阅。
- 超限时只返回安全超限错误，不返回已读取正文片段。
- HTTP 工具取消、截止、执行线程中断或完成状态未知后不重试请求；若连接建立阶段明确报告 `executionStarted=false` 的瞬时失败，可按通用治理规则最多重试一次。即使 GET 具有幂等性，自动重试也不得突破共享 deadline 和预算。

## Result and error mapping

| Situation | Model-visible result | Network side effect |
|-----------|----------------------|---------------------|
| 白名单 URL、2xx、正文未超限 | 脱敏且受治理长度限制的正文 | 允许 |
| 非白名单/非法协议/user-info | 安全沙箱拒绝 | 零请求或零后续跳转 |
| 非 2xx | 状态码和固定安全说明 | 已发生的请求只记录状态 |
| 缺少/非法 Location | 安全重定向错误 | 不发起下一跳 |
| 超过重定向上限 | 安全重定向错误 | 不发起下一跳 |
| 正文超限 | 不含正文的安全超限错误 | 取消读取 |
| 共享截止时间耗尽 | 安全超时错误并终止本轮 | 取消后续等待 |

## Configuration

`agentos.tools.http`：

| Key | Default | Boundary |
|-----|---------|----------|
| `allowed-domains` | empty | exact host or `*.subdomain` |
| `user-agent` | `AgentOS/0.1` | non-empty |
| `max-redirects` | 3 | 0..3 |
| `max-response-bytes` | 1 MiB | 1..1 MiB |
| `timeout` | 30s | positive |
| `max-result-chars` | 10000 | 普通治理结果字符上限，正整数；截断标记计入上限 |
