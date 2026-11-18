#!/usr/bin/env python3
"""US1 手动验收替身：只监听本机，不连接真实模型或网站，不记录消息正文。"""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class ManualServer(ThreadingHTTPServer):
    def __init__(self, address):
        super().__init__(address, Handler)
        self.guard = threading.Lock()
        self.mode = "normal"
        self.events = []
        self.http_requests = 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        # HTTP 默认日志可能包含查询参数；验收只查看下方的安全统计。
        pass

    def reply(self, status, value):
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/probe":
            with self.server.guard:
                self.server.http_requests += 1
            self.reply(200, {"message": "US1_HTTP_OK"})
        elif self.path == "/__stats":
            with self.server.guard:
                self.reply(200, {
                    "mode": self.server.mode,
                    "model_requests": len(self.server.events),
                    "http_requests": self.server.http_requests,
                    "events": self.server.events,
                })
        else:
            self.reply(404, {"error": "未知替身路径"})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 1048576:
                raise ValueError()
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError()
        except (ValueError, UnicodeDecodeError):
            self.reply(400, {"error": "替身只接受不超过 1 MiB 的 JSON 对象"})
            return

        if self.path == "/__control":
            mode = body.get("mode", "normal")
            if mode not in {"normal", "force-http", "force-unknown"}:
                self.reply(400, {"error": "未知替身模式"})
                return
            with self.server.guard:
                self.server.mode = mode
                self.server.events.clear()
                self.server.http_requests = 0
            self.reply(200, {"mode": mode, "message": "模式已切换，统计已清零"})
            return

        if self.path.rstrip("/") != "/v1/chat/completions":
            self.reply(404, {"error": "未知替身路径"})
            return

        definitions = []
        for tool in body.get("tools") or []:
            function = tool.get("function", {})
            parameters = function.get("parameters") or {}
            definitions.append({
                "name": function.get("name"),
                "description_present": bool(str(function.get("description") or "").strip()),
                "parameters_type": parameters.get("type"),
                "parameter_names": sorted((parameters.get("properties") or {}).keys()),
            })
        names = [item["name"] for item in definitions]
        messages = body.get("messages") or []
        # 只检查最后的工具结果，避免把前一轮历史误判为本次结果。
        tool_results = []
        for message in reversed(messages):
            if message.get("role") != "tool":
                break
            tool_results.append(str(message.get("content") or ""))
        result_kind = "none"
        if tool_results:
            if any("US1_HTTP_OK" in value for value in tool_results):
                result_kind = "ok"
            elif any("没有名为" in value for value in tool_results):
                result_kind = "denied"
            else:
                result_kind = "other"

        with self.server.guard:
            mode = self.server.mode
            emitted_tool = None
            if not tool_results:
                if mode == "force-unknown":
                    emitted_tool = "outside"
                elif mode == "force-http" or "http_get" in names:
                    emitted_tool = "http_get"
            event = {
                "tools_field_present": "tools" in body,
                "visible_tools": names,
                "definitions": definitions,
                "emitted_tool": emitted_tool,
                "tool_result_kind": result_kind,
            }
            self.server.events.append(event)
            sequence = len(self.server.events)
        print(json.dumps(event, ensure_ascii=False), flush=True)

        message = {"role": "assistant"}
        finish_reason = "stop"
        if emitted_tool:
            finish_reason = "tool_calls"
            message["content"] = None
            message["tool_calls"] = [{
                "id": f"manual-call-{sequence}",
                "type": "function",
                "function": {
                    "name": emitted_tool,
                    "arguments": json.dumps({
                        "url": f"http://127.0.0.1:{self.server.server_port}/probe",
                    }),
                },
            }]
        else:
            message["content"] = {
                "none": "本地模型没有获得工具定义，本轮直接回答。",
                "ok": "已收到 US1_HTTP_OK，授权工具闭环完成。",
                "denied": "已收到未授权工具错误，本轮继续并正常结束。",
                "other": "收到非预期工具结果，请检查统计和审计状态。",
            }[result_kind]
        self.reply(200, {
            "id": f"manual-response-{sequence}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": body.get("model", "manual-us1-model"),
            "choices": [{"index": 0, "message": message, "finish_reason": finish_reason}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18181)
    args = parser.parse_args()
    server = ManualServer(("127.0.0.1", args.port))
    print(f"手动验收服务已启动：http://127.0.0.1:{server.server_port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
