#!/usr/bin/env python3
"""标准库打包验收：隔离workspace、Docker PostgreSQL、本地模型/HTTP/MCP，不读取.env。"""
import argparse
import datetime
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid

TOOLS = ['http_get', 'read_file', 'write_file', 'list_dir', 'shell', 'http_post',
         'edit_file', 'grep', 'glob', 'ask_user', 'web_search']


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True,
                          timeout=kwargs.pop('timeout', 60), **kwargs).stdout


def validate_evidence(value):
    """检查本harness输出使用的schema子集，schema自身由最终验收用Draft202012Validator复核。"""
    schema = json.loads(Path(__file__).with_name('acceptance-evidence.schema.json').read_text())
    assert set(value) <= set(schema['properties'])
    assert set(schema['required']) <= set(value)
    assert value['timezone'] == 'Asia/Shanghai'
    assert value['recordedAt'].endswith('+08:00')
    assert value['status'] in ('PASS', 'FAIL', 'NOT_RUN')
    assert (value['status'] == 'NOT_RUN') == (not value['executed'])
    assert value['executed'] or value.get('reason')
    assert set(value['counts']) == set(schema['properties']['counts']['required'])
    assert all(isinstance(n, int) and n >= 0 for n in value['counts'].values())
    assert value['evidence']


class Fixture(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.server.actions += 1
        body = {'web': {'results': [{'title': '技术资料', 'url': 'https://example.org/article',
                                    'description': 'Spring AI 2.0提供ToolCallback'}]}} if self.path.startswith('/search') else {'city': '北京', 'temperature': 22}
        self.reply(body)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if self.path != '/v1/chat/completions':
            self.server.actions += 1
            self.reply({'accepted': True})
            return
        data = json.loads(body)
        self.server.requests.append(data)
        if self.server.probing:
            self.reply({'id': 'probe', 'object': 'chat.completion', 'model': 'test-model',
                        'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': '来源探测完成'}, 'finish_reason': 'stop'}]})
            self.server.probe_ready.set()
            return
        messages = data['messages']
        feedback = [m for m in messages if m.get('role') == 'tool']
        if feedback:
            self.reply({'id': 'offline', 'object': 'chat.completion', 'model': 'test-model',
                        'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': '离线工具验收完成'}, 'finish_reason': 'stop'}]})
        else:
            calls = [{'id': 'call-' + name, 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args)}}
                     for name, args in self.server.script]
            self.reply({'id': 'offline', 'object': 'chat.completion', 'model': 'test-model',
                        'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': '', 'tool_calls': calls}, 'finish_reason': 'tool_calls'}]})

    def reply(self, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def run(jar, output):
    output.mkdir(parents=True, exist_ok=True)
    container = None
    server = None
    counts = dict(actions=0, auditBegin=0, auditComplete=0, cleanupConfirmed=0)
    status = 'FAIL'
    detail = {}
    try:
        with tempfile.TemporaryDirectory(prefix='agentos-tools-') as temporary:
            root = Path(temporary)
            workspace = root / 'workspace'
            workspace.mkdir()
            (workspace / 'sample.txt').write_text('before target after', encoding='utf-8')
            forbidden_name = 'AGENTOS_UNAUTHORIZED_TEST_CREDENTIAL'
            forbidden_value = 'forbidden-host-fixture-sentinel'
            business_value = 'explicit-business-fixture-sentinel'
            (workspace / 'control.json').write_text(json.dumps({'inspectEnv': [forbidden_name, 'BUSINESS_VALUE']}))
            python = Path(sys.executable).resolve()
            mcp = Path(__file__).with_name('mcp_test_server.py').resolve()
            mcp_args = [str(mcp), '--transport', 'stdio', '--control', str(workspace / 'control.json'),
                        '--stats', str(workspace / 'stats.json'), '--ready', str(workspace / 'ready.json')]
            (workspace / 'mcp_servers.yaml').write_text('servers:\n  - name: fixture\n    transport: stdio\n    command: '
                + json.dumps(str(python)) + '\n    args: ' + json.dumps(mcp_args) + '\n    cwd: ' + json.dumps(str(workspace))
                + '\n    env:\n      BUSINESS_VALUE: ' + json.dumps(business_value) + '\n')
            for name in ('allowed', 'denied'):
                agent = workspace / 'agents' / name
                agent.mkdir(parents=True)
                (agent / 'AGENT.md').write_text('---\nname: ' + name + '\nprovider:\n  model: test-model\ntools: '
                    + json.dumps(TOOLS + ['mcp_fixture__echo'] if name == 'allowed' else [])
                    + '\nmcp_servers: [fixture]\n---\n离线验收\n')
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
            server.daemon_threads = True
            server.actions = 0
            server.requests = []
            server.probing = False
            server.probe_ready = threading.Event()
            url = 'http://127.0.0.1:' + str(server.server_port)
            server.script = [
                ('http_get', {'url': url + '/weather'}),
                ('read_file', {'path': 'sample.txt'}),
                ('write_file', {'path': 'created.txt', 'content': 'new'}),
                ('list_dir', {'path': '.'}),
                ('shell', {'executable': str(python), 'args': ['-c',
                    "import os,json; from pathlib import Path; "
                    "v={n:os.environ.get(n) for n in ['AGENTOS_UNAUTHORIZED_TEST_CREDENTIAL','BUSINESS_VALUE']}; "
                    "Path('action.txt').write_text(json.dumps(v)); print(json.dumps(v))"]}),
                ('http_post', {'url': url + '/post', 'body': '{}'}),
                ('edit_file', {'path': 'sample.txt', 'old_text': 'target', 'new_text': 'changed'}),
                ('grep', {'path': '.', 'text': 'changed'}), ('glob', {'path': '.', 'pattern': '*.txt'}),
                ('ask_user', {'question': '无人值守测试'}), ('web_search', {'query': 'Spring AI'}),
                ('mcp_fixture__echo', {'value': 'business'})]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            container = 'agentos-tool-acceptance-' + uuid.uuid4().hex[:12]
            env = dict(os.environ, POSTGRES_PASSWORD='isolated-fixture-password')
            command(['docker', 'run', '--detach', '--name', container, '--env', 'POSTGRES_PASSWORD',
                     '--publish', '127.0.0.1::5432', 'pgvector/pgvector:pg17'], env=env)
            end = time.monotonic() + 40
            while subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'postgres'], capture_output=True).returncode:
                if time.monotonic() >= end:
                    raise RuntimeError('隔离PostgreSQL未就绪')
                time.sleep(.1)
            port = command(['docker', 'port', container, '5432/tcp']).strip().rsplit(':', 1)[-1]
            config = root / 'application.yaml'
            # JSON为YAML子集。配置只有隔离测试凭证，不含个人环境/真实服务凭证。
            config.write_text(json.dumps({'spring': {'config': {'import': ''}, 'datasource': {
                'url': 'jdbc:postgresql://127.0.0.1:' + port + '/postgres', 'username': 'postgres', 'password': 'isolated-fixture-password'}},
                'logging': {'file': {'name': str(root / 'run.log')}, 'level': {'root': 'OFF'}},
                'agentos': {'embedding': {'enabled': False}, 'providers': {'primary': {'type': 'openai', 'base-url': url + '/v1', 'api-key': 'isolated-model-fixture'}},
                'tools': {'http': {'allowed-domains': ['127.0.0.1']}, 'file': {'read-allowed-paths': [str(workspace)], 'write-allowed-paths': [str(workspace)]},
                          'shell': {'commands': [{'executable': str(python), 'allowed-cwds': [str(workspace)],
                                                 'env': {'BUSINESS_VALUE': business_value}}]},
                          'search': {'endpoint': url + '/search', 'api-key': 'isolated-search-fixture'}}}}))
            def query(sql):
                return command(['docker', 'exec', container, 'psql', '-U', 'postgres', '-d', 'postgres', '-Atc', sql]).strip()
            for agent in ('allowed', 'denied'):
                before = server.actions
                server.requests.clear()
                process = subprocess.Popen(['java', '-jar', str(jar), '--config', str(config), 'chat', '--workspace', str(workspace),
                                            '--profile', agent], cwd=root, text=True, stdin=subprocess.PIPE,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                           env=dict(os.environ, **{forbidden_name: forbidden_value}))
                probes = 0
                try:
                    if agent == 'allowed':
                        # 后台发现不阻塞本地启动；用同一进程的真实轮次快照探测就绪，禁止靠固定sleep假定可用。
                        server.probing = True
                        end = time.monotonic() + 45
                        while True:
                            server.probe_ready.clear()
                            process.stdin.write('来源就绪探测\n')
                            process.stdin.flush()
                            while not server.probe_ready.wait(.1):
                                if process.poll() is not None or time.monotonic() >= end:
                                    raise RuntimeError('来源就绪探测未完成')
                            probes += 1
                            names = {t['function']['name'] for t in server.requests[-1].get('tools', [])}
                            if 'mcp_fixture__echo' in names:
                                break
                            if time.monotonic() >= end:
                                raise RuntimeError('MCP来源未就绪')
                        server.probing = False
                        server.requests.clear()
                    dialogue = '离线验收\n' + ('验收回答\n' if agent == 'allowed' else '') + '/exit\n'
                    text, errors = process.communicate(dialogue, timeout=75)
                    assert process.returncode == 0
                finally:
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.communicate(timeout=5)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.communicate(timeout=5)
                assert '离线工具验收完成' in text
                assert len(server.requests) == 2
                definitions = [t['function']['name'] for t in server.requests[0].get('tools', [])]
                assert set(definitions) == (set(TOOLS + ['mcp_fixture__echo']) if agent == 'allowed' else set())
                feedback = [m for m in server.requests[-1]['messages'] if m.get('role') == 'tool']
                assert {m['tool_call_id'] for m in feedback} == {'call-' + n for n, _ in server.script}
                assert server.actions - before == (3 if agent == 'allowed' else 0)
                if agent == 'allowed':
                    assert (workspace / 'sample.txt').read_text() == 'before changed after'
                    assert (workspace / 'created.txt').read_text() == 'new'
                    assert json.loads((workspace / 'action.txt').read_text()) == {
                        forbidden_name: None, 'BUSINESS_VALUE': business_value}
                    remote = next(m['content'] for m in feedback if m['tool_call_id'] == 'call-mcp_fixture__echo')
                    # 响应中的null证明宿主变量不可读；显式变量已读取，并由治理层遮罩。
                    assert forbidden_name in remote and 'null' in remote and 'BUSINESS_VALUE' in remote and '***' in remote
                    assert forbidden_value not in json.dumps(feedback) and business_value not in json.dumps(feedback)
                    # 记录真实持有进程已在应用关闭时退出，不能替工具杀掉它。
                    pid = json.loads((workspace / 'stats.json').read_text())['pid']
                    try:
                        os.kill(pid, 0)
                        raise AssertionError('MCP进程未退出')
                    except ProcessLookupError:
                        counts['cleanupConfirmed'] += 1
                detail[agent] = {'readinessProbes': probes, 'modelCalls': 2, 'definitions': sorted(definitions), 'feedbackIds': sorted(m['tool_call_id'] for m in feedback), 'networkActions': server.actions - before}
            summary = json.loads(query("select json_build_object('rows',count(*),'complete',count(*) filter(where status <> 'in_progress'),'success',count(*) filter(where status='success'),'denied',count(*) filter(where attempts=0)) from tool_invocations"))
            assert summary == {'rows': 24, 'complete': 24, 'success': 12, 'denied': 12}, summary
            audit_text = query('select input_json::text || coalesce(result_text,\'\') from tool_invocations')
            assert forbidden_value not in audit_text and business_value not in audit_text
            logs = (root / 'run.log').read_text() if (root / 'run.log').exists() else ''
            assert forbidden_value not in logs and business_value not in logs
            catalog = command(['java', '-jar', str(jar), '--config', str(config), 'tool', 'list', '--workspace', str(workspace), '--json'],
                              cwd=root, timeout=45, env=dict(os.environ, **{forbidden_name: forbidden_value}))
            assert forbidden_value not in catalog and business_value not in catalog
            counts.update(actions=summary['success'], auditBegin=summary['rows'], auditComplete=summary['complete'])
            detail['audits'] = summary
            detail['environmentIsolation'] = {'hostCredentialInjected': True, 'shellDeniedRead': True,
                'stdioDeniedRead': True, 'explicitBusinessRead': True, 'safeFeedbackAuditLogCatalog': True}
            (output / 'packaged-details.json').write_text(json.dumps(detail, ensure_ascii=False, indent=2))
            status = 'PASS'
    except Exception as error:
        # 不反射子进程输出/命令/凭证；只保存固定源码中的失败位置，便于重放定位。
        trace = error.__traceback__
        failure_line = None
        while trace:
            if trace.tb_frame.f_code.co_filename == __file__:
                failure_line = trace.tb_lineno
            trace = trace.tb_next
        detail = {'failureClass': type(error).__name__, 'failureLine': failure_line}
        (output / 'packaged-details.json').write_text(json.dumps(detail))
    finally:
        if server:
            server.shutdown(); server.server_close()
        if container:
            subprocess.run(['docker', 'rm', '--force', container], capture_output=True, timeout=15)
    value = {'status': status, 'executed': True, 'recordedAt': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
             'timezone': 'Asia/Shanghai', 'command': 'python3 fixtures/full_tool_acceptance.py --jar AgentOS.jar --output evidence',
             'counts': counts, 'evidence': ['packaged-details.json']}
    validate_evidence(value)
    (output / 'packaged-evidence.json').write_text(json.dumps(value, ensure_ascii=False, indent=2))
    print('打包隔离验收：' + status)
    return 0 if status == 'PASS' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sys.exit(run(args.jar.resolve(strict=True), args.output.resolve()))
