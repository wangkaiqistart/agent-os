#!/usr/bin/env python3
"""检查 .env 中的模型与 Embedding 配置是否可用。

只读取 .env，不打印任何 key。用法（在仓库根目录）：
    python3 scripts/check_env.py
"""
import json
import ssl
import time
import urllib.error
import urllib.request

# 指定系统根证书，仍然校验证书（python.org 安装的 Python 默认找不到系统证书）
CTX = ssl.create_default_context(cafile='/etc/ssl/cert.pem')


def load_env(path='.env'):
    env = {}
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            env[k] = v
    return env


def post(url, key, body, timeout=120):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode(), method='POST',
        headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    t = time.time()
    try:
        r = urllib.request.urlopen(req, timeout=timeout, context=CTX)
        return r.status, r.read().decode('utf-8', 'ignore'), round(time.time() - t, 1)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'ignore')[:400], round(time.time() - t, 1)
    except Exception as e:  # 网络、证书等错误
        return None, f'{type(e).__name__}: {e}', round(time.time() - t, 1)


def short(text, n=250):
    return text[:n].replace('\n', ' ')


def check_placeholders(env):
    print('== 0. .env 占位符检查')
    for k, v in env.items():
        if '<' in v or '>' in v:
            print(f'  ⚠ {k} 仍是占位符，需要填真实值')
        elif not v:
            print(f'  - {k} 为空（可选项可忽略）')


def check_llm(env):
    url = env['AGENTOS_LLM_BASE_URL'].rstrip('/') + '/v1/chat/completions'
    key, model = env['AGENTOS_LLM_API_KEY'], env['AGENTOS_LLM_MODEL']
    tools = [{"type": "function", "function": {
        "name": "get_weather", "description": "查询城市天气",
        "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}]
    msgs = [{"role": "user", "content": "北京今天天气怎么样？请调用工具查询。"}]

    print('\n== 1. 主链路：同步 + 工具调用')
    st, raw, sec = post(url, key, {"model": model, "messages": msgs, "tools": tools,
                                   "reasoning_effort": "low", "max_completion_tokens": 800})
    print('  HTTP', st, f'{sec}s')
    try:
        j = json.loads(raw)
        c = j['choices'][0]
        print('  实际模型:', j.get('model'), '| finish_reason:', c.get('finish_reason'))
        print('  tool_calls:', short(json.dumps(c['message'].get('tool_calls'), ensure_ascii=False), 300))
        print('  usage:', short(json.dumps(j.get('usage'), ensure_ascii=False), 400))
    except Exception:
        print('  ', short(raw, 400))

    print('\n== 2. 主链路：流式 + 工具调用 + 用量统计')
    st, raw, sec = post(url, key, {"model": model, "messages": msgs, "tools": tools, "stream": True,
                                   "stream_options": {"include_usage": True},
                                   "reasoning_effort": "low", "max_completion_tokens": 800})
    print('  HTTP', st, f'{sec}s')
    lines = [l[6:] for l in raw.splitlines() if l.startswith('data: ') and l[6:] != '[DONE]']
    usage = [l for l in lines if '"usage":{' in l.replace(' ', '')]
    print('  分片数:', len(lines), '| 有 tool_calls 分片:', any('tool_calls' in l for l in lines),
          '| 有 usage 分片:', bool(usage))
    if usage:
        print('  usage:', json.loads(usage[-1]).get('usage'))
    if not lines:
        print('  ', short(raw, 300))

    print('\n== 3. 主链路：发送 temperature=0.5（推理模型可能拒绝）')
    st, raw, sec = post(url, key, {"model": model, "messages": [{"role": "user", "content": "回复 OK"}],
                                   "temperature": 0.5, "max_completion_tokens": 50})
    print('  HTTP', st, f'{sec}s', short(raw) if st != 200 else '接受了 temperature')

    print('\n== 4. 主链路：json_schema 结构化输出')
    st, raw, sec = post(url, key, {
        "model": model, "messages": [{"role": "user", "content": "给出城市北京和人口（万）"}],
        "response_format": {"type": "json_schema", "json_schema": {"name": "city", "strict": True, "schema": {
            "type": "object", "properties": {"name": {"type": "string"}, "population": {"type": "number"}},
            "required": ["name", "population"], "additionalProperties": False}}},
        "reasoning_effort": "low", "max_completion_tokens": 300})
    print('  HTTP', st, f'{sec}s', end=' ')
    try:
        print('content:', short(json.loads(raw)['choices'][0]['message']['content'], 200))
    except Exception:
        print(short(raw))


def check_embedding(env):
    url = env['AGENTOS_EMBEDDING_BASE_URL'].rstrip('/') + '/v1/embeddings'
    key, model = env['AGENTOS_EMBEDDING_API_KEY'], env['AGENTOS_EMBEDDING_MODEL']
    dim = int(env['AGENTOS_EMBEDDING_DIMENSIONS'])

    print('\n== 5. Embedding：维度校验')
    st, raw, sec = post(url, key, {"model": model, "input": ["ping", "北京天气"],
                                   "dimensions": dim, "encoding_format": "float"}, timeout=30)
    print('  HTTP', st, f'{sec}s', end=' ')
    try:
        j = json.loads(raw)
        lens = [len(d['embedding']) for d in j['data']]
        ok = all(n == dim for n in lens)
        print('| 向量长度:', lens, '| 期望:', dim, '✓' if ok else '✗ 不一致', '| usage:', j.get('usage'))
    except Exception:
        print(short(raw, 300))

    print('\n== 6. Embedding：一次 11 条（文档写单次最多 10 条）')
    st, raw, sec = post(url, key, {"model": model, "input": [f"文本{i}" for i in range(11)],
                                   "dimensions": dim}, timeout=30)
    print('  HTTP', st, f'{sec}s', short(raw) if st != 200 else f'返回 {len(json.loads(raw)["data"])} 条，上限比文档宽')


if __name__ == '__main__':
    e = load_env()
    check_placeholders(e)
    check_llm(e)
    check_embedding(e)
