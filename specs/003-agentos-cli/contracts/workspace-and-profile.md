# Workspace and Profile Contract

**Owner**: `AgentOS-cli` / `AgentOS-core`

## Initialization files

A successful first initialization creates or preserves:

```text
<root>/
├── agents/main/AGENT.md
├── skills/
├── output/
├── memory/MEMORY.md
├── logs/
├── AGENTS.md
├── SOUL.md
└── USER.md
```

Existing valid files are never overwritten. File and directory type conflicts, unsafe final symlinks, and write failures are errors. A failed initialization must not claim readiness; a retry may only fill missing items and must revalidate an existing `main`.

The generated `main/AGENT.md` contains a concrete `provider.model`, `provider.name: primary` unless a valid explicit provider was supplied, `tools: []`, and the default Chinese assistant instruction. The model and provider values are serialized as data; they cannot add YAML fields or write credentials.

## Model and Provider inputs

Provider 名称校验使用全局 `--config <path>` 指定的配置文件。未指定时按 `contracts/cli.md` 的固定本地搜索顺序查找；显式路径不存在、不可读或格式错误时返回 2，不回退。CLI 与 Boot 使用同一文件格式，CLI 只读取 `agentos.providers` 的名称键，不读取凭据或连接值。

| Input | Rule |
|---|---|
| `--model` | Required for creating missing `main`; explicit and concrete value only |
| Environment / `.env` | Never read by this feature for initialization |
| `--provider` omitted | Write `primary` into new `main` |
| `--provider` supplied | Nonblank name present in the local non-sensitive `agentos.providers.<name>` projection; unknown name fails before write without network access |
| Existing valid `main` | Preserve byte-for-byte and do not require new model input |

The initialization command only records names. Provider connectivity, database access and model authorization are chat/runtime concerns.
