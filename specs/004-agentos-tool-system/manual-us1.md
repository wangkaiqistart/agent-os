# Phase 3 / US1 手动验收

本次验收检查 Agent 的工具名单、模型实际收到的工具定义、错误配置零模型外呼，以及运行时未授权调用零底层执行。本地替身代替模型和 HTTP 网站，操作仍是逐条手动执行真实 CLI；不依赖百度、真实模型的回答或搜索质量。

运行会使用当前开发数据库，并新增专属测试 Agent 的会话、轮次和审计记录。每次准备生成唯一的 Agent 名称，不修改 main、不归档 main 的会话，不清库。配置继承现有数据库连接；模型地址明确覆盖为本机替身，并关闭 Embedding。

开始前确认使用 JDK 21、Python 3，以及可连接的开发 PostgreSQL（pgvector）数据库。若 JAR 尚未包含当前分支代码，先在项目根目录执行 `mvn -B -ntp verify`，成功后再进行以下步骤；测试使用 Testcontainers，需要 Docker 可用。

## 1. 终端 A：启动本地替身

```bash
cd /Volumes/Data/my_project/agent-os
python3 specs/004-agentos-tool-system/fixtures/us1_manual_server.py
```

预期出现 `手动验收服务已启动：http://127.0.0.1:18181`。保留此终端；端口已占用时先处理占用，不要继续后续步骤。

替身只输出工具名称、定义摘要、工具结果类别及请求统计，不输出消息正文、工具参数或凭证。`normal` 模式按授权调用；`force-http` 和 `force-unknown` 模式专门模拟模型越权。

## 2. 终端 B：准备独立验收配置和 Agent

保持以下命令在同一个终端执行，后续步骤会使用这里的变量。

```bash
cd /Volumes/Data/my_project/agent-os
us1_dir=$(mktemp -d /tmp/agentos-us1.XXXXXX)
us1_tag=us1-$(date +%Y%m%d%H%M%S)

cat > "$us1_dir/application.yaml" <<'YAML'
agentos:
  providers:
    primary:
      type: openai
      base-url: http://127.0.0.1:18181/v1
      api-key: local-manual-placeholder
      timeout: 5s
      retry-min-remaining: 1s
  embedding:
    enabled: false
  tools:
    http:
      allowed-domains:
        - 127.0.0.1
YAML

java -jar AgentOS-boot/target/AgentOS.jar \
  --config "$us1_dir/application.yaml" init \
  --workspace "$us1_dir/workspace" --model manual-us1-model

python3 - "$us1_dir" "$us1_tag" <<'PY'
import re
import sys
from pathlib import Path

root, tag = Path(sys.argv[1]), sys.argv[2]
assert re.fullmatch(r'us1-[0-9]{14}', tag)
cases = {
    'http': '[http_get]',
    'none': '[]',
    'unknown': '[does_not_exist]',
    'duplicate': '[http_get, http_get]',
}
for suffix, tools in cases.items():
    name = f'{tag}-{suffix}'
    directory = root / 'workspace' / 'agents' / name
    directory.mkdir(parents=True)
    (directory / 'AGENT.md').write_text(
        f'---\nname: {name}\nprovider:\n  name: primary\n'
        f'  model: manual-us1-model\ntools: {tools}\n---\n'
        '仅执行本次手动验收。\n', encoding='utf-8')
values = ',\n'.join(f"('{tag}-{suffix}')" for suffix in cases)
names = ', '.join(f"'{tag}-{suffix}'" for suffix in cases)
(root / 'evidence.sql').write_text(f'''
-- 只读统计：unknown 和 duplicate 的各项计数应始终为 0。
WITH profiles(profile_name) AS (VALUES {values})
SELECT p.profile_name,
  (SELECT count(*) FROM sessions s WHERE s.profile_name=p.profile_name) AS sessions,
  (SELECT count(*) FROM agent_turns t JOIN sessions s USING(session_id) WHERE s.profile_name=p.profile_name) AS turns,
  (SELECT count(*) FROM session_messages m JOIN sessions s USING(session_id) WHERE s.profile_name=p.profile_name) AS messages,
  (SELECT count(*) FROM llm_calls l JOIN sessions s USING(session_id) WHERE s.profile_name=p.profile_name) AS model_calls,
  (SELECT count(*) FROM tool_invocations i JOIN sessions s USING(session_id) WHERE s.profile_name=p.profile_name) AS tool_records
FROM profiles p ORDER BY p.profile_name;

-- 只读审计明细：不查询 prompt、工具参数或响应正文。
SELECT s.profile_name, t.turn_id, t.status AS turn_status,
  t.error_message AS turn_error, i.iteration, i.tool_name,
  i.status AS tool_status, i.error_message AS tool_error
FROM sessions s JOIN agent_turns t USING(session_id)
LEFT JOIN tool_invocations i ON i.turn_id=t.turn_id
WHERE s.profile_name IN ({names})
ORDER BY s.profile_name, t.started_at, i.iteration, i.id;
''', encoding='utf-8')
print('测试 Agent 前缀：', tag)
print('只读查证 SQL：', root / 'evidence.sql')
PY
```

预期：init 成功，四个测试 Agent 已准备。`local-manual-placeholder` 是本地替身占位值，不是真实密钥；这份配置不会访问真实模型或 Embedding 服务。

## 3. 用例一：合法授权只暴露 http_get

```bash
curl -sS -X POST http://127.0.0.1:18181/__control \
  -H 'Content-Type: application/json' -d '{"mode":"normal"}'

java -jar AgentOS-boot/target/AgentOS.jar \
  --config "$us1_dir/application.yaml" chat \
  --workspace "$us1_dir/workspace" --profile "$us1_tag-http" \
  --message '执行授权工具检查'
echo "退出码：$?"

curl -sS http://127.0.0.1:18181/__stats | python3 -m json.tool
```

通过条件：

- CLI 输出 `已收到 US1_HTTP_OK，授权工具闭环完成。`，退出码 0。
- `model_requests` 为 2，`http_requests` 为 1。
- 首次请求的 `visible_tools` 只有 `http_get`。
- 工具的 `description_present` 为 true、`parameters_type` 为 `object`，`parameter_names` 只有 `url`，没有内部上下文字段。
- 第二次请求的 `tool_result_kind` 为 `ok`。
- 数据库中本轮 `turn_status=SUCCEEDED`、`tool_name=http_get`、`tool_status=success`。

## 4. 用例二：空工具集合不发送 tools 字段

```bash
curl -sS -X POST http://127.0.0.1:18181/__control \
  -H 'Content-Type: application/json' -d '{"mode":"normal"}'

java -jar AgentOS-boot/target/AgentOS.jar \
  --config "$us1_dir/application.yaml" chat \
  --workspace "$us1_dir/workspace" --profile "$us1_tag-none" \
  --message '执行空工具集合检查'
echo "退出码：$?"

curl -sS http://127.0.0.1:18181/__stats | python3 -m json.tool
```

通过条件：CLI 输出 `本地模型没有获得工具定义，本轮直接回答。`，退出码 0；`model_requests=1`、`http_requests=0`、`tools_field_present=false`、`visible_tools=[]`；本轮没有工具审计记录。

不能只凭模型说“我没有工具”判定通过，必须同时看替身收到的请求统计。

## 5. 用例三：未知工具配置在模型外呼前失败

```bash
curl -sS -X POST http://127.0.0.1:18181/__control \
  -H 'Content-Type: application/json' -d '{"mode":"normal"}'

java -jar AgentOS-boot/target/AgentOS.jar \
  --config "$us1_dir/application.yaml" chat \
  --workspace "$us1_dir/workspace" --profile "$us1_tag-unknown" \
  --message '这条消息不得进入模型'
echo "退出码：$?"

curl -sS http://127.0.0.1:18181/__stats | python3 -m json.tool
```

通过条件：退出码 2；`model_requests=0`、`http_requests=0`；执行 `evidence.sql` 后，`unknown` Agent 的 sessions/turns/messages/model_calls/tool_records 均为 0。

CLI 还必须显示 `agents/<本次 Agent 名称>/AGENT.md 的 tools 包含未注册工具：does_not_exist`，同时不显示异常堆栈、消息正文或底层 cause。只有拒绝时机、零外呼、零数据库记录和错误定位全部满足，才判定本项 PASS。

## 6. 用例四：重复授权配置失败

```bash
curl -sS -X POST http://127.0.0.1:18181/__control \
  -H 'Content-Type: application/json' -d '{"mode":"normal"}'

java -jar AgentOS-boot/target/AgentOS.jar \
  --config "$us1_dir/application.yaml" chat \
  --workspace "$us1_dir/workspace" --profile "$us1_tag-duplicate" \
  --message '这条消息不得进入模型'
echo "退出码：$?"

curl -sS http://127.0.0.1:18181/__stats | python3 -m json.tool
```

通过条件：CLI 提示无法解析该 Agent 文件，退出码 2；模型请求数和 HTTP 请求数均为 0；`duplicate` Agent 的数据库各项统计均为 0。这验证的是 Agent 名单重复，与全局工具目录注册重名属于两个不同场景。

## 7. 用例五：模型强行调用未授权的已存在工具

使用 `tools: []` 的 Agent，由替身强行返回 `http_get` 调用。不要通过自然语言诱导真实模型，真实模型未必会发出非法调用。

```bash
curl -sS -X POST http://127.0.0.1:18181/__control \
  -H 'Content-Type: application/json' -d '{"mode":"force-http"}'

java -jar AgentOS-boot/target/AgentOS.jar \
  --config "$us1_dir/application.yaml" chat \
  --workspace "$us1_dir/workspace" --profile "$us1_tag-none" --new \
  --message '执行未授权工具调用检查'
echo "退出码：$?"

curl -sS http://127.0.0.1:18181/__stats | python3 -m json.tool
```

通过条件：

- 首次请求仍没有 `tools` 字段，替身的 `emitted_tool=http_get`。
- `http_requests=0`，证明真实 HTTP 工具未执行。
- `model_requests=2`，第二次 `tool_result_kind=denied`。
- CLI 输出 `已收到未授权工具错误，本轮继续并正常结束。`，退出码 0。
- 新轮次 `SUCCEEDED`，有一条 `http_get` 的失败拒绝审计，`tool_status=failed`；不是成功工具记录，也不是域名沙箱拒绝。

`--new` 只归档本次唯一名称的测试 Agent 会话，确保用例二的历史不会影响本用例。

## 8. 用例六：模型调用根本不存在的工具

```bash
curl -sS -X POST http://127.0.0.1:18181/__control \
  -H 'Content-Type: application/json' -d '{"mode":"force-unknown"}'

java -jar AgentOS-boot/target/AgentOS.jar \
  --config "$us1_dir/application.yaml" chat \
  --workspace "$us1_dir/workspace" --profile "$us1_tag-http" --new \
  --message '执行不存在工具调用检查'
echo "退出码：$?"

curl -sS http://127.0.0.1:18181/__stats | python3 -m json.tool
```

通过条件：首次模型请求可见工具只有 `http_get`，替身强行返回 `outside`；`http_requests=0`、`model_requests=2`、第二次 `tool_result_kind=denied`；CLI 正常结束，轮次 `SUCCEEDED`，工具审计为 `outside/failed`。

## 9. 数据库查证与记录结果

在终端 B 查看准备阶段生成的只读 SQL：

```bash
cat "$us1_dir/evidence.sql"
```

复制到当前开发数据库连接中执行。该 SQL 只查询本次唯一前缀的测试 Agent，不读取消息正文、工具输入或结果正文。

六个用例依次各执行一次后的统计应为：

| Agent 后缀 | sessions | turns | messages | model_calls | tool_records |
|---|---:|---:|---:|---:|---:|
| http | 2 | 2 | 8 | 4 | 2 |
| none | 2 | 2 | 6 | 3 | 1 |
| unknown | 0 | 0 | 0 | 0 | 0 |
| duplicate | 0 | 0 | 0 | 0 | 0 |

如果重跑某个用例，累计计数会增加；按 turn_id 和本地替身每次清零后的统计判断，不把累计值当成本次请求次数。

每个用例记录 CLI 退出码、`/__stats` JSON、对应 turn_id 和安全审计状态。连接失败、超时或持久化未确认不能算授权用例 PASS。

## 10. 本轮手动验收的边界

上述步骤覆盖 CLI 可构造的授权、精确工具定义、空集合、未知配置、重复授权及运行时安全拒绝。以下属于代码注册与装配契约，当前只有一个内置工具，无法仅修改 Agent 文件构造，应单独通过现有契约测试或 IDE 调试验收：

- 全局工具目录注册重名、缺少名称/描述/inputSchema、非法 Schema；
- 多个注册工具的精确子集与声明顺序；
- returnDirect 元数据保持；
- 同名 Bean 不被解析，以及只有一个官方 Manager/resolver/processor 的装配。

记录时将这些项目列为本轮手动验收未覆盖，不把六个 CLI 用例通过等同于 T009–T016 的全部内部契约均通过。Profile 级域名白名单不属于本次 Phase 3。

完成后在终端 A 按 Ctrl+C 停止替身；临时配置无需提交。数据库验收记录保留，不执行 clean、repair 或批量删除。
