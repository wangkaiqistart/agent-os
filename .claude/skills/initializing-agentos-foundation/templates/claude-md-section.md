## 工程基线

质量门全部绑定在 `mvn verify`，CI（`.github/workflows/ci.yml`）跑的是同一条命令。**`mvn verify` 失败 = 违反规范**，修代码，不要放宽规则或加排除项来让它通过（确需调整先问用户）。

| 检查 | 工具 | 失败时怎么办 |
|------|------|-------------|
| 格式（Google Java Style，AOSP 4 空格） | Spotless + google-java-format | `mvn spotless:apply` |
| 命名、导入、空 catch、switch 缺 default | Checkstyle（`config/checkstyle/checkstyle.xml`） | 按报错位置改 |
| 缺陷与安全（注入、弱加密、路径穿越） | SpotBugs + FindSecBugs | 改代码；误报用 `@SuppressFBWarnings(value=..., justification=...)` 并写原因 |
| 禁用依赖（alibaba、H2、SQLite）、JDK 21 | maven-enforcer-plugin | 删依赖 |
| 设计约束（ToolCallingAdvisor、自建 ToolCallingManager、ThreadLocal、System.out、字段注入、core 依赖实现模块） | ArchUnit（`AgentOS-boot` 的 `ArchitectureRulesTest`） | 按约束改实现 |
| 密钥泄露 / 源码漏洞 / 依赖漏洞 | gitleaks、CodeQL、Dependabot（`security.yml`、`dependabot.yml`） | 泄露的密钥先轮换，再清理 |

工具查不到、需要自觉遵守的约定：

- 依赖用构造器注入；日志用 SLF4J 占位符（`log.info("x={}", x)`），不拼字符串，不打印密钥、prompt 全文和工具参数。
- MDC 只用于日志关联，约定键 `sessionId`、`turnId`、`agent`、`iteration`；业务上下文走 `ToolContext`，不从 MDC 读。
- 业务异常抛 `AgentOSException(ErrorCode, message)`，message 会返回给调用方，不能含内部细节；新增错误码要在 `GlobalExceptionHandler.toHttpStatus` 补映射。
- REST 响应统一用 `ApiResponse` 信封，Controller 不自己 try/catch 拼错误响应。
