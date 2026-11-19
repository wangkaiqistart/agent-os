#!/usr/bin/env python3
"""隔离MCP协议替身，仅标准库；测试数据不得替换为真实凭证。"""
import argparse
import http.server
import json
import os
import queue
import sys
import threading
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--transport', choices=('stdio', 'sse', 'streamable-http'), required=True)
parser.add_argument('--control', required=True)
parser.add_argument('--stats', required=True)
parser.add_argument('--ready', required=True)
options = parser.parse_args()
state_lock = threading.RLock()
state = {'pid': os.getpid(), 'initialize': 0, 'list': 0, 'call': 0, 'closed': 0,
         'requests': 0, 'credentialRequests': 0, 'calls': [], 'connections': 0}
sessions = {}


def atomic_json(path, value):
    target = Path(path)
    temporary = target.with_suffix('.pending')
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
    temporary.replace(target)


def control():
    try:
        return json.loads(Path(options.control).read_text(encoding='utf-8'))
    except FileNotFoundError:
        return {}


def update(key, amount=1):
    with state_lock:
        state[key] += amount
        atomic_json(options.stats, state)


def request(message):
    config = control()
    method = message.get('method')
    if method == 'notifications/initialized':
        return None
    if 'id' not in message:
        return None
    if method == 'initialize':
        update('initialize')
        time.sleep(config.get('initDelay', 0))
        result = {'protocolVersion': message.get('params', {}).get('protocolVersion', '2025-03-26'),
                  'capabilities': {'tools': {'listChanged': False}},
                  'serverInfo': {'name': 'fixture', 'version': '1.0'}}
    elif method == 'tools/list':
        update('list')
        time.sleep(config.get('pageDelay', 0))
        count = config.get('toolCount', 1)
        page_size = config.get('pageSize', 200)
        offset = int(message.get('params', {}).get('cursor') or 0)
        names = config.get('names')
        if names is None:
            names = ['echo'] if count == 1 else ['echo_' + str(i) for i in range(count)]
        tools = [{'name': name, 'description': 'fixture tool', 'inputSchema': config.get('schema', {
            'type': 'object', 'properties': {'value': {'type': 'string'}}, 'additionalProperties': False})}
                 for name in names[offset:offset + page_size]]
        result = {'tools': tools}
        if offset + page_size < len(names):
            result['nextCursor'] = str(offset + page_size)
    elif method == 'tools/call':
        with state_lock:
            state['call'] += 1
            state['calls'].append({'params': message.get('params', {}), 'meta': message.get('_meta')})
            atomic_json(options.stats, state)
        if config.get('disconnectCall'):
            return 'DISCONNECT'
        time.sleep(config.get('callDelay', 0))
        text = json.dumps({'fixture': 'echo', 'arguments': message.get('params', {}).get('arguments', {}),
                           'environment': {name: os.environ.get(name) for name in config.get('inspectEnv', [])}})
        if config.get('largeResponse'):
            text = 'x' * 1048600
        if config.get('errorText'):
            text = config['errorText']
        result = {'content': [{'type': 'text', 'text': text}], 'isError': bool(config.get('isError'))}
    elif method == 'ping':
        result = {}
    else:
        return {'jsonrpc': '2.0', 'id': message['id'], 'error': {'code': -32601, 'message': 'fixture unsupported'}}
    return {'jsonrpc': '2.0', 'id': message['id'], 'result': result}


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def count_request(self):
        update('requests')
        if self.headers.get('Authorization'):
            update('credentialRequests')

    def send_json(self, status, value=None):
        body = b'' if value is None else json.dumps(value).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)
            self.wfile.flush()

    def do_GET(self):
        self.count_request()
        if options.transport != 'sse' or not self.path.startswith('/sse'):
            self.send_json(405)
            return
        config = control()
        if config.get('redirect'):
            self.send_response(302)
            self.send_header('Location', config['redirect'])
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        session = str(time.monotonic_ns())
        messages = queue.Queue()
        with state_lock:
            sessions[session] = messages
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()
        endpoint = config.get('messageEndpoint', '/messages?session=' + session)
        update('connections')
        try:
            self.wfile.write(('event: endpoint\ndata: ' + endpoint + '\n\n').encode('utf-8'))
            self.wfile.flush()
            while True:
                try:
                    message = messages.get(timeout=.05)
                    if message == 'DISCONNECT':
                        break
                    self.wfile.write(('event: message\ndata: ' + json.dumps(message) + '\n\n').encode('utf-8'))
                except queue.Empty:
                    self.wfile.write(b': fixture heartbeat\n\n')
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with state_lock:
                sessions.pop(session, None)
            update('closed')
            self.close_connection = True

    def do_POST(self):
        self.count_request()
        length = int(self.headers.get('Content-Length', '0'))
        message = json.loads(self.rfile.read(length))
        result = request(message)
        if options.transport == 'sse':
            self.send_json(202)
            session = self.path.split('session=', 1)[-1]
            with state_lock:
                destination = sessions.get(session)
            if destination is not None and result is not None:
                destination.put(result)
        elif result == 'DISCONNECT':
            self.connection.shutdown(2)
            self.close_connection = True
        elif result is None:
            self.send_json(202)
        else:
            self.send_json(200, result)

    def do_DELETE(self):
        self.count_request()
        update('closed')
        self.send_json(200, {})


atomic_json(options.stats, state)
if options.transport == 'stdio':
    atomic_json(options.ready, {'pid': os.getpid(), 'port': 0})
    try:
        for line in sys.stdin.buffer:
            result = request(json.loads(line))
            if result == 'DISCONNECT':
                break
            if result is not None:
                sys.stdout.write(json.dumps(result) + '\n')
                sys.stdout.flush()
    finally:
        update('closed')
else:
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    atomic_json(options.ready, {'pid': os.getpid(), 'port': server.server_address[1]})
    server.serve_forever()
