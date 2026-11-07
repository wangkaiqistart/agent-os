---
name: initializing-agentos-foundation
description: >
  Use when AgentOS 要开始写业务代码、但工程地基还没铺：日志、监控、HTTP Server、API 规范（统一响应与错误码）、
  Java 开发规范（Google Java Style、Checkstyle）、代码安全检查（SpotBugs/FindSecBugs、gitleaks、CodeQL、Dependabot）、
  架构约束测试（ArchUnit）、CI 质量门。用户说「初始化基础模块」「铺工程基线」「加开发规范和安全检查」
  「第十五节课」，或仓库根 pom 还没有 spotless/checkstyle/spotbugs 插件时使用。
disable-model-invocation: true
---

# 初始化 AgentOS 工程基线

## 概述

在 US-1 之前一次性铺好横切能力。**核心原则：能用工具检查的规范一律进 `mvn verify`，不靠提示词。** 构建失败就是违反规范；工具管不到的少数约定写进 CLAUDE.md。

下文的 `templates/`、`scripts/` 指本 skill 目录（`<skill>` = 本 SKILL.md 所在目录的绝对路径）。模板已按 AOSP 格式化，在 AgentOS 骨架（9 个模块）上实测通过，共 22 个测试。

## 范围

| 做 | 不做（属于后续 user story 或扩展阶段） |
|----|------|
| 质量门：enforcer、Spotless、Checkstyle、SpotBugs + FindSecBugs、ArchUnit | 业务端点（10 个 REST 端点在 US-5） |
| API 规范：`ErrorCode`、`AgentOSException`、`ApiResponse`、`GlobalExceptionHandler` | `/api/v1/health` 业务端点（US-5） |
| 日志：文件 ECS JSON + MDC 约定；HTTP：端口、优雅停机、CORS | MDC 的写入逻辑（US-2 的 `ReActLoop`） |
| 监控：Actuator 只暴露 health；关闭 prompt / 工具内容进日志和 Span | Prometheus 导出、OpenTelemetry、认证、限流 |
| CI：`ci.yml`、`security.yml`、`dependabot.yml`、`.gitleaks.toml` | 修改 `.specify/memory/constitution.md`（要改先问用户） |

## 已定决策（不要改成别的）

| 项 | 选择 | 依据 |
|----|------|------|
| 代码格式 | google-java-format **AOSP**（4 空格） | 与现有代码和文档一致；用户要纯 Google 风格时把 `<style>` 改成 `GOOGLE` |
| 错误响应 | `ApiResponse` 信封（`code`/`message`/`data`/`timestamp`），**不是 ProblemDetail** | 技术方案 7.1 |
| 错误码 | 400 `INVALID_ARGUMENT`、404 `NOT_FOUND`、409 `SESSION_BUSY`、500 `INTERNAL_ERROR`、503 `PROVIDER_UNAVAILABLE` | 技术方案 7.4 |
| 依赖漏洞 | Dependabot，不用 OWASP dependency-check | 后者需要 NVD API key，首次下载很慢 |

## 步骤

1. **前置检查。** `git status` 必须干净，否则停下问用户。跑一次 `mvn -B verify`，把结果（模块数、测试数）记下来写进最后的汇报。读 CLAUDE.md「不可违反的设计约束」和技术方案 7.1、7.4。
2. **质量门。** 把 `templates/pom-build.xml` 按注释分四段合并进根 `pom.xml`：① properties 追加到已有 `<properties>` 末尾；② archunit 追加到已有 `<dependencyManagement><dependencies>` 末尾；③④ 骨架没有 `<dependencies>` 和 `<build>`，新建在 `</dependencyManagement>` 之后。复制 `templates/config/` 到仓库根 `config/`。
3. **API 规范与测试。** 把 `templates/java/` 下全部文件复制到仓库同名路径：core 的 `error` 包，web 的 `api`、`config` 包和测试，boot 的 `ArchitectureRulesTest`、`LoggingBaselineTest`。
4. **监控与日志。** `AgentOS-boot/pom.xml` 的 `<dependencies>` 末尾加 `spring-boot-starter-actuator` 和 `archunit`（`<scope>test</scope>`，不写版本，由第 2 步管理）。把 `templates/application-baseline.yaml` **逐键**合并进 `AgentOS-boot/src/main/resources/application.yaml`（文件头注释说明了每段合并到哪）。`.gitignore` 末尾加 `.AgentOS/logs/`。
5. **安全与 CI。** 把 `templates/github/` 下的文件**新增**到 `.github/` 同名路径，已有文件（如 `deploy-website.yml`）不动。`templates/gitleaks.toml` 复制为仓库根 `.gitleaks.toml`。
6. **CLAUDE.md。** ① 把 `templates/claude-md-section.md` 插到「## 官网」之前。② 在「## 命令」代码块第一行之前加：
   ```
   mvn -B verify                     # 质量门：格式、Checkstyle、SpotBugs、依赖禁令、ArchUnit、测试
   mvn spotless:apply                # 自动格式化
   ```
   ③ 「## 项目现状」里"每个模块只有 `package-info.java`""测试都还没写"已不成立，改成实际状态（有 API 规范类和基线测试，业务代码与 Flyway 脚本还没写）。
7. **格式化并验证。**
   ```bash
   mvn -B -ntp spotless:apply      # 会重排骨架里现有的 cli 文件，属于预期
   python3 <skill>/scripts/check_foundation.py --mvn
   ```
   在仓库根目录运行，必须输出「全部通过」。
8. **证明门禁有效。** 在 `AgentOS-core` 临时加一个类，同时写 `ThreadLocal` 字段、`System.out.println`、`ToolCallingManager.builder()`。先 `mvn spotless:apply`，再跑 `mvn -B verify -Dspotbugs.skip=true -Dcheckstyle.skip=true`（core 模块的格式或风格检查失败会让构建在 boot 之前停下，ArchUnit 就不会运行）。确认 `ArchitectureRulesTest` 报出这三处违规后**删掉这个类**，再跑一次 `mvn -B verify` 回到全绿。
9. **汇报。** 列出改动文件、第 1 步与最终的测试数对比、第 8 步拦截结果。提交前先问用户。

## 常见错误

| 错误 | 后果 | 正确做法 |
|------|------|---------|
| ArchUnit 加 `ImportOption.DO_NOT_INCLUDE_JARS` | `mvn verify` 时兄弟模块是 `target/*.jar`，被排除后只剩 boot 自己；加上 `allowEmptyShould(true)`，所有规则**静默通过** | 只用 `importPackages("com.agentos")` 过滤；保留 `importsEveryModule()` 这条防空转测试 |
| 直接用 Checkstyle 自带的 `google_checks.xml` | 按 2 空格查缩进，与 AOSP 格式化结果冲突，全仓报错 | 用模板里的精简规则，排版只交给 Spotless |
| YAML 里另起一个 `spring:` 段 | 后一个顶层键覆盖前一个，原有配置静默丢失 | 合并进已有的 `spring:`、`spring.ai.chat:`、`spring.ai.tools:` 段 |
| 用 `mvn verify -rf :AgentOS-boot` 续跑 | 兄弟模块没 install，依赖解析失败 | 直接全量 `mvn verify`（约 30 秒） |
| 顺手加 Prometheus / OpenTelemetry / ProblemDetail | 越过核心阶段范围，与技术方案冲突 | 只做「范围」表左列 |
| `verify` 失败时放宽规则、加排除项 | 门禁形同虚设 | 修代码；确是误报才加排除并写明原因，先问用户 |
| gitleaks 扫到历史里的真实密钥后加进 allowlist | 密钥仍然可用 | 先轮换密钥，再决定是否清理历史 |
