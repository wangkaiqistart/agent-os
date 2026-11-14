# AgentOS 类结构图

这份文件记录当前仓库里已经存在的 Java 类，以及它们之间的主要关系。图中的关系以当前源码为准；设计文档里提到但源码中尚未出现的类，不放进“当前类图”。

## 一、先看箭头含义

```text
继承：       子类 --|> 父类
实现接口：   实现类 ..|> 接口
使用依赖：   A --> B
组合关系：   A *-- B
```

类图里最重要的是先回答三个问题：

1. 谁负责创建对象？
2. 谁依赖谁？
3. 哪些类只是接口，哪些类是真正实现？

## 二、当前项目总览

```mermaid
flowchart TD
    Boot[AgentOSApplication] --> CLI[AgentOSCli]
    CLI --> Command[AgentOSCommand]
    Boot --> Config[application.yaml]
    Config --> ProviderConfig[ProviderAutoConfiguration]
    ProviderConfig --> Provider[Provider 模块]
    Provider --> Core[AgentOS-core 契约]
    Web[AgentOS-web] --> Core
    CLI --> Core
```

当前代码的方向是：

```text
boot 负责组装
cli / web 负责接收请求
core 负责定义契约
provider 负责连接外部模型
```

## 三、AgentOS-core 类图

```mermaid
classDiagram
    class ErrorCode {
        <<enumeration>>
        INVALID_ARGUMENT
        NOT_FOUND
        SESSION_BUSY
        PROVIDER_UNAVAILABLE
        INTERNAL_ERROR
    }

    class AgentOSException {
        -ErrorCode errorCode
        +getErrorCode() ErrorCode
    }

    class Profile {
        +String provider
        +String model
        +String reasoningEffort
        +Integer maxOutputTokens
        +Double temperature
    }

    class CallCorrelation {
        +UUID sessionId
        +UUID turnId
        +Integer iteration
        +none() CallCorrelation
    }

    class ProviderRequest {
        +Profile profile
        +List~Message~ messages
        +List~ToolCallback~ tools
        +Map~String,Object~ toolContext
        +Instant deadline
        +CallCorrelation correlation
        +springToolContext() ToolContext
    }

    class ProviderService {
        <<interface>>
        +call(ProviderRequest) ProviderResult
        +stream(ProviderRequest, Consumer) ProviderResult
    }

    class ProviderResult {
        +ChatResponse response
        +List~LlmCallRecord~ calls
    }

    class LlmCallRecord {
        +UUID sessionId
        +String provider
        +String requestedModel
        +CallMode mode
        +int attempt
        +boolean success
        +FailureKind failureKind
        +long durationMs
    }

    class LlmCallRecorder {
        <<interface>>
        +record(LlmCallRecord)
    }

    class ProviderCallException {
        +List~LlmCallRecord~ calls
        +getCalls() List~LlmCallRecord~
    }

    class CallMode {
        <<enumeration>>
        CALL
        STREAM
    }

    class FailureKind {
        <<enumeration>>
        RATE_LIMITED
        SERVER_ERROR
        CONNECTION
        AUTH
        INVALID_REQUEST
        PROTOCOL
        CANCELLED
    }

    AgentOSException --> ErrorCode : carries
    Profile ..> AgentOSException : validates
    ProviderRequest *-- Profile
    ProviderRequest *-- CallCorrelation
    ProviderService ..> ProviderRequest : receives
    ProviderService ..> ProviderResult : returns
    ProviderResult *-- LlmCallRecord
    LlmCallRecorder ..> LlmCallRecord : records
    ProviderCallException --|> AgentOSException
    ProviderCallException *-- LlmCallRecord
    LlmCallRecord --> CallMode
    LlmCallRecord --> FailureKind
```

最核心的阅读顺序是：

```text
Profile
  ↓
ProviderRequest
  ↓
ProviderService
  ↓
ProviderResult
  ↓
LlmCallRecord / LlmCallRecorder
```

`ProviderService` 是接口，所以 `core` 不知道底层到底是 OpenAI、DeepSeek 还是测试服务器。

## 四、Provider 模块类图

```mermaid
classDiagram
    class ProviderAutoConfiguration {
        +loggingLlmCallRecorder() LlmCallRecorder
    }

    class ChatModelFactory {
        +create(Connection) OpenAiChatModel
    }

    class ProviderProperties {
        +String defaultProvider
        +Map~String,Connection~ providers
    }

    class Connection {
        +String type
        +String baseUrl
        +String apiKey
        +Duration timeout
        +Duration retryMinRemaining
        +Duration retryMaxWait
    }

    class EmbeddingProperties {
        +boolean enabled
        +String baseUrl
        +String apiKey
        +String model
        +int dimensions
        +int maxBatchSize
        +Duration timeout
    }

    class ConnectionType {
        <<enumeration>>
        OPENAI
        DEEPSEEK
        +parse(String) ConnectionType
        +applyOutputLimit(Builder, Integer) Builder
    }

    class ProviderConnection {
        +String name
        +ConnectionType type
        +Duration timeout
        +boolean available
        +OpenAiChatModel chatModel
    }

    class LoggingLlmCallRecorder {
        +record(LlmCallRecord)
    }

    class ToolCallingManager {
        <<Spring AI Bean>>
    }

    class ObservationRegistry {
        <<Micrometer Bean>>
    }

    class OpenAiChatModel {
        <<Spring AI class>>
    }

    ProviderAutoConfiguration --> ProviderProperties : enables
    ProviderAutoConfiguration --> EmbeddingProperties : enables
    ProviderAutoConfiguration ..> LoggingLlmCallRecorder : creates default
    ChatModelFactory --> ToolCallingManager : injects
    ChatModelFactory --> ObservationRegistry : injects
    ChatModelFactory --> ProviderProperties : reads Connection
    ChatModelFactory --> OpenAiChatModel : creates, not Bean
    ProviderProperties *-- Connection : contains map values
    ProviderConnection --> ConnectionType
    LoggingLlmCallRecorder ..|> LlmCallRecorder
    LoggingLlmCallRecorder ..> LlmCallRecord
```

这里的关键是：

```text
application.yaml
    ↓
ProviderProperties / EmbeddingProperties
    ↓
ProviderAutoConfiguration
    ↓
Spring 容器中的 Bean
```

## 五、启动、CLI 与 Web 类图

```mermaid
classDiagram
    class AgentOSApplication {
        +main(String[]) void
    }

    class AgentOSCli {
        +execute(String...) int
    }

    class AgentOSCommand {
        +run() void
    }

    class VersionInfo {
        +getVersion() String[]
    }

    class ApiResponse~T~ {
        +String code
        +String message
        +T data
        +Instant timestamp
        +ok(T) ApiResponse
        +error(ErrorCode, String) ApiResponse
    }

    class GlobalExceptionHandler {
        +handleAgentOS(AgentOSException)
        +handleUnexpected(Exception)
        +toHttpStatus(ErrorCode) HttpStatus
    }

    class WebConfig {
        +addCorsMappings(CorsRegistry)
    }

    class ResponseEntityExceptionHandler {
        <<Spring class>>
    }

    class WebMvcConfigurer {
        <<Spring interface>>
    }

    AgentOSApplication --> AgentOSCli : delegates
    AgentOSCli --> AgentOSCommand : creates
    AgentOSCommand --> VersionInfo : versionProvider
    GlobalExceptionHandler --|> ResponseEntityExceptionHandler
    GlobalExceptionHandler --> ApiResponse : returns
    GlobalExceptionHandler --> ErrorCode : maps
    WebConfig ..|> WebMvcConfigurer
```

当前实际启动链路仍然是：

```text
AgentOSApplication.main
    ↓
AgentOSCli.execute
    ↓
AgentOSCommand
    ↓
显示帮助或执行子命令
```

`GlobalExceptionHandler` 目前已经可以工作，但真正的 `ApiController` 还没有加入。

## 六、当前不存在的类

以下类在设计文档中出现，但当前源码中还没有实现，因此没有画进“当前类图”：

```text
ReActLoop
AgentService
ToolExecutor
MemoryService
SessionManager
LongTermMemoryStore
ToolRegistry
Sandbox
ApiController
Repository
```

等这些类真正加入源码后，再把它们补进对应模块的类图。

