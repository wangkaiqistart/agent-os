"""004 CLI 黑盒验收：仅使用专用本地容器与回环替身，不读取项目 .env。"""
import datetime, hashlib, json, os, pathlib, subprocess, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = pathlib.Path(__file__).resolve().parents[3]
JAR = REPO / 'AgentOS-boot/target/AgentOS.jar'
RUN = pathlib.Path(tempfile.mkdtemp(prefix='agentos-004-blackbox-'))
CONTAINER = 'agentos-004-blackbox-' + str(os.getpid())
STATE = {}
RESULTS = []
SYNTHETIC = 'SYNTHETIC_004_PRIVATE'

def cmd(args, **kw):
    return subprocess.run(args, text=True, capture_output=True, check=True, **kw).stdout.strip()

def sql(query):
    return json.loads(cmd(['docker', 'exec', CONTAINER, 'psql', '-U', 'acceptance', '-d', 'acceptance', '-At', '-c', query]))

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass
    def send(self, code, body, headers=None):
        self.send_response(code)
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError): pass
    def do_GET(self):
        STATE['http'] += 1
        if STATE['case'] == 'normal':
            STATE['intent_before_http'] = sql("select coalesce(bool_or(i.status='in_progress'),false)::text from tool_invocations i join sessions s using(session_id) where s.profile_name='bb-normal'")
        if self.path.startswith('/redirect-denied'):
            return self.send(302, b'', {'Location': f'http://localhost:{self.server.server_port}/probe'})
        if self.path.startswith('/redirect/'):
            n = int(self.path.split('?')[0].split('/')[-1])
            return self.send(302, b'', {'Location': '/probe' if n == 1 else '/redirect/' + str(n-1)})
        if self.path.startswith('/oversize'):
            return self.send(200, (SYNTHETIC.encode() + b'X' * 1048577))
        if self.path.startswith('/status'):
            return self.send(503, SYNTHETIC.encode())
        if self.path.startswith('/slow'):
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            try:
                for _ in range(200):
                    self.wfile.write(b'chunk\n'); self.wfile.flush(); time.sleep(0.1)
            except (BrokenPipeError, ConnectionResetError): pass
            return
        return self.send(200, json.dumps({'message': 'HTTP_OK', 'token': SYNTHETIC}).encode())
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        STATE['model'] += 1
        names = [t['function']['name'] for t in body.get('tools', [])]
        STATE['visible'].append(names)
        STATE['tools_present'].append('tools' in body)
        if body.get('tools'):
            fields = body['tools'][0]['function']['parameters']['properties']
            STATE['schema_url_only'] = list(fields) == ['url']
        messages = body['messages']
        tool_results = [m for m in messages if m['role'] == 'tool']
        STATE['history_safe'] &= SYNTHETIC not in json.dumps(messages)
        if tool_results:
            STATE['result_ids'].extend(m['tool_call_id'] for m in tool_results)
            text = '\n'.join(m.get('content', '') for m in tool_results)
            STATE['result_safe'] &= SYNTHETIC not in text
            STATE['safe_error_seen'] |= any(x in text for x in ['没有名为', '沙箱拒绝', '正文超过', '503', '未执行', '上限'])
        case = STATE['case']
        calls = []
        if case not in ['empty'] and (not tool_results or case == 'model_limit'):
            name = 'outside' if case == 'runtime_unknown' else 'http_get'
            path = {'oversize':'/oversize', 'http_status':'/status', 'slow_body':'/slow', 'redirect_denied':'/redirect-denied', 'redirect_three':'/redirect/3'}.get(case, '/probe')
            host = 'localhost' if case == 'sandbox_denied' else '127.0.0.1'
            url = f'http://{host}:{self.server.server_port}{path}?token={SYNTHETIC}'
            def call(cid, args):
                return {'id':cid, 'type':'function','function':{'name':name,'arguments':args}}
            args = json.dumps({'url':url})
            if case == 'malformed_batch':
                calls = [call('good',args), call('bad','{"broken":')]
            elif case == 'tool_limit':
                calls = [call('one',args),call('two',args),call('three',args)]
            else:
                calls = [call('call-' + str(STATE['model']),args)]
        message = {'role':'assistant', 'content':None, 'tool_calls':calls} if calls else {'role':'assistant','content':'BLACKBOX_OK'}
        response = {'id':'response-' + str(STATE['model']), 'object':'chat.completion','created':int(time.time()),'model':'local-test','choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if calls else 'stop'}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}}
        self.send(200,json.dumps(response).encode())

server = ThreadingHTTPServer(('127.0.0.1',0), Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
env = {k:v for k,v in os.environ.items() if not k.startswith(('AGENTOS_', 'SPRING_', 'LOGGING_')) and k not in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS']}
cleanup = False
container_created = False
try:
    cmd(['docker','run','--rm','-d','--name',CONTAINER,'-e','POSTGRES_USER=acceptance','-e','POSTGRES_PASSWORD=local-test-placeholder','-e','POSTGRES_DB=acceptance','-p','127.0.0.1::5432','pgvector/pgvector:pg17'])
    container_created = True
    for _ in range(100):
        ready = subprocess.run(['docker','exec',CONTAINER,'pg_isready','-U','acceptance','-d','acceptance'],capture_output=True)
        if ready.returncode == 0: break
        time.sleep(0.2)
    else: raise AssertionError('isolated PostgreSQL did not become ready')
    port = cmd(['docker','port',CONTAINER,'5432/tcp']).rsplit(':',1)[-1]
    for case in ['normal','empty','unknown_config','duplicate_config','runtime_unauthorized','runtime_unknown','sandbox_denied','redirect_denied','redirect_three','oversize','http_status','malformed_batch','tool_limit','model_limit','slow_body']:
        STATE.clear(); STATE.update(case=case, model=0, http=0, visible=[], tools_present=[], history_safe=True, result_safe=True, result_ids=[], safe_error_seen=False, schema_url_only=None, intent_before_http=None)
        name = 'bb-' + case.replace('_','-')
        workspace = RUN/'workspace'
        agent = workspace/'agents'/name
        agent.mkdir(parents=True)
        tools = '[]' if case in ['empty','runtime_unauthorized'] else '[does_not_exist]' if case == 'unknown_config' else '[http_get, http_get]' if case == 'duplicate_config' else '[http_get]'
        timeout = '2s' if case == 'slow_body' else '10s'
        iterations = 3 if case == 'model_limit' else 10
        (agent/'AGENT.md').write_text(f'---\nname: {name}\nprovider:\n  name: primary\n  model: local-test\ntools: {tools}\nsettings:\n  max_iterations: {iterations}\n  turn_timeout: {timeout}\n---\n执行隔离验收。PROMPT_SENTINEL_004\n')
        config = RUN/'application.yaml'
        config.write_text(f'''spring:
  config:
    import: ""
  datasource:
    url: jdbc:postgresql://127.0.0.1:{port}/acceptance
    username: acceptance
    password: local-test-placeholder
agentos:
  providers:
    primary:
      type: openai
      base-url: http://127.0.0.1:{server.server_port}/v1
      api-key: local-test-placeholder
      timeout: 5s
      retry-min-remaining: 1s
    deepseek:
      api-key: ""
  embedding:
    enabled: false
  tools:
    http:
      allowed-domains: [127.0.0.1]
logging:
  file:
    name: {RUN/case}.log
''')
        envcase = env | ({'SPRING_AI_TOOLS_LIMITS_MAXCALLSPERTOOLDEFAULT':'2'} if case == 'tool_limit' else {})
        start = time.monotonic()
        process = subprocess.run(['java','-jar',str(JAR),'--config',str(config),'chat','--workspace',str(workspace),'--profile',name,'--message','执行本地工具验收 USER_SENTINEL_004'], cwd=RUN,env=envcase,capture_output=True,text=True,timeout=45)
        duration = round((time.monotonic()-start)*1000)
        output = process.stdout + process.stderr
        (RUN/(case+'.out')).write_text(output)
        data = sql(f"""select json_build_object(
          'sessions',(select count(*) from sessions where profile_name='{name}'),
          'turns',(select count(*) from agent_turns t join sessions s using(session_id) where s.profile_name='{name}'),
          'messages',(select count(*) from session_messages m join sessions s using(session_id) where s.profile_name='{name}'),
          'llm',(select count(*) from llm_calls l join sessions s using(session_id) where s.profile_name='{name}'),
          'turn_status',(select t.status from agent_turns t join sessions s using(session_id) where s.profile_name='{name}' limit 1),
          'tool_rows',(select coalesce(json_agg(json_build_object('id',i.tool_call_id,'status',i.status,'attempts',i.attempts)), '[]'::json) from tool_invocations i join sessions s using(session_id) where s.profile_name='{name}'),
          'db_safe', not exists(select 1 from tool_invocations i join sessions s using(session_id) where s.profile_name='{name}' and (i.input_json::text || coalesce(i.result_text,'') || coalesce(i.error_message,'')) like '%{SYNTHETIC}%') and not exists(select 1 from session_messages m join sessions s using(session_id) where s.profile_name='{name}' and coalesce(m.tool_calls::text,'') like '%{SYNTHETIC}%'))""")
        expected = {'normal':(0,2,1,['success']), 'empty':(0,1,0,[]),'unknown_config':(2,0,0,[]),'duplicate_config':(2,0,0,[]),'runtime_unauthorized':(0,2,0,['failed']),'runtime_unknown':(0,2,0,['failed']),'sandbox_denied':(0,2,0,['sandbox_denied']),'redirect_denied':(0,2,1,['sandbox_denied']),'redirect_three':(0,2,4,['success']),'oversize':(0,2,1,['failed']),'http_status':(0,2,1,['failed']),'malformed_batch':(0,2,0,['failed','failed']),'tool_limit':(0,2,2,['limit_skipped','success','success']),'model_limit':(3,3,3,['success','success','success']),'slow_body':(3,1,1,['timeout'])}[case]
        checks = {
          'exit':process.returncode == expected[0], 'model_count':STATE['model'] == expected[1], 'http_count':STATE['http'] == expected[2],
          'statuses':sorted(x['status'] for x in data['tool_rows']) == sorted(expected[3]),
          'history_safe':STATE['history_safe'], 'result_safe':STATE['result_safe'], 'db_safe':data['db_safe'], 'output_safe':all(x not in output for x in [SYNTHETIC, 'PROMPT_SENTINEL_004', 'USER_SENTINEL_004']),
          'log_safe':all(x not in (RUN/(case+'.log')).read_text() for x in [SYNTHETIC, 'PROMPT_SENTINEL_004', 'USER_SENTINEL_004']) if (RUN/(case+'.log')).exists() else case == 'duplicate_config',
          'llm_rows':data['llm'] == STATE['model'],
          'visible_subset':all(n == ([] if tools == '[]' else ['http_get']) for n in STATE['visible'])}
        if case in ['unknown_config','duplicate_config']:
            checks['zero_records'] = data['sessions'] == data['turns'] == data['messages'] == data['llm'] == len(data['tool_rows']) == 0
            checks['diagnostic'] = f'agents/{name}/AGENT.md' in output and 'tools' in output and ('does_not_exist' if case == 'unknown_config' else 'http_get') in output
        elif case == 'model_limit': checks['turn_status'] = data['turn_status'] == 'MAX_ITERATIONS'
        elif case == 'slow_body': checks['turn_status'] = data['turn_status'] == 'TIMED_OUT'
        else: checks['turn_status'] = data['turn_status'] == 'SUCCEEDED'
        if case == 'normal':
            checks['intent_first'] = STATE['intent_before_http'] is True
            checks['response_id'] = STATE['result_ids'] == ['call-1']
        if case in ['empty','runtime_unauthorized']: checks['no_tools_field'] = not any(STATE['tools_present'])
        if STATE['schema_url_only'] is not None: checks['schema_url_only'] = STATE['schema_url_only']
        if case == 'malformed_batch':
            checks['zero_attempts'] = all(x['attempts'] == 0 for x in data['tool_rows'])
            checks['response_ids'] = sorted(STATE['result_ids']) == ['bad','good']
        if case == 'tool_limit':
            checks['response_ids'] = sorted(STATE['result_ids']) == ['one','three','two']
            checks['skip_zero_attempts'] = all(x['attempts'] == 0 for x in data['tool_rows'] if x['status'] == 'limit_skipped')
        row = {'case':case,'result':'PASS' if all(checks.values()) else 'FAIL','exit':process.returncode,'model_requests':STATE['model'],'http_requests':STATE['http'],'process_ms':duration,'database':data,'checks':checks}
        RESULTS.append(row)
        (RUN/'evidence.json').write_text(json.dumps(RESULTS,ensure_ascii=False,indent=2))
        print(json.dumps({k:v for k,v in row.items() if k not in ['database','checks']}),flush=True)
        if row['result'] == 'FAIL': print(json.dumps({'failed_checks':[k for k,v in checks.items() if not v],'run_dir':str(RUN)}),flush=True)
finally:
    server.shutdown(); server.server_close()
    if container_created:
        cleanup = subprocess.run(['docker','rm','-f',CONTAINER],capture_output=True).returncode == 0
    artifact = {'completed_at':datetime.datetime.now().astimezone().isoformat(),'jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'run_dir':str(RUN),'temporary_container_removed':cleanup,'cases':RESULTS}
    (RUN/'evidence.json').write_text(json.dumps(artifact,ensure_ascii=False,indent=2))
    print(json.dumps({'evidence':str(RUN/'evidence.json'),'passed':sum(r['result']=='PASS' for r in RESULTS),'total':len(RESULTS),'container_removed':cleanup}),flush=True)
if len(RESULTS) != 15 or any(r['result'] != 'PASS' for r in RESULTS): raise SystemExit(1)
