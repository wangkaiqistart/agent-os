# AgentOS-web 代码导读

Web 模块负责 HTTP 表达，不负责模型推理。核心原则是所有成功和失败响应都使用统一格式。

```mermaid
sequenceDiagram
    participant C as HTTP 客户端
    participant MVC as Spring MVC
    participant H as GlobalExceptionHandler
    C->>MVC: 请求
    MVC-->>C: ApiResponse 成功
    MVC->>H: Controller 抛出异常
    H-->>C: ApiResponse 错误 + HTTP 状态码
```

## 类的职责

| 类 | 作用 |
|---|---|
| `ApiResponse<T>` | 统一的 `code/message/data/timestamp` 响应信封 |
| `GlobalExceptionHandler` | 把业务异常和 Spring MVC 异常转成统一响应 |
| `WebConfig` | 配置 `/api/**` 的 CORS |

例如：

```json
{
  "code": "SESSION_BUSY",
  "message": "会话正有轮次在执行",
  "data": null,
  "timestamp": "..."
}
```

当前还没有真正的 `ApiController`，所以这里是 Web 基础设施，而不是完整 REST API。

