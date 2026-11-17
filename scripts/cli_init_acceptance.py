#!/usr/bin/env python3
"""执行 AgentOS CLI 初始化验收矩阵并输出 JSON/Markdown 统计。"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import tempfile
import time
from pathlib import Path


def run(command: list[str], config: Path, workspace: Path, model: str, provider: str | None = None):
    args = command + ["--config", str(config), "init", "--workspace", str(workspace), "--model", model]
    if provider:
        args += ["--provider", provider]
    started = time.perf_counter()
    completed = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    elapsed = time.perf_counter() - started
    return completed.returncode, elapsed, completed.stdout, completed.stderr


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * p)))
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--command", default="java -jar AgentOS-boot/target/AgentOS.jar")
    parser.add_argument("--config", type=Path)
    parser.add_argument("--runs", type=int, default=20)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()
    command = shlex.split(args.command)
    config_temp = None
    if args.config is None:
        config_temp = tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False)
        config_temp.write("agentos:\n  providers:\n    primary:\n      type: openai\n")
        config_temp.close()
        config = Path(config_temp.name)
    else:
        config = args.config

    result: dict[str, object] = {
        "status": "PASS",
        "runs": args.runs,
        "warmup": args.warmup,
        "initialization": {"success": 0, "failures": 0},
        "idempotency": {"runs": 0, "content_changes": 0, "failures": 0},
        "unknown_provider": {"runs": 0, "exit_2": 0, "writes": 0, "failures": 0},
    }
    timings: list[float] = []
    try:
        for _ in range(args.warmup):
            with tempfile.TemporaryDirectory() as directory:
                code, _, _, _ = run(command, config, Path(directory) / "warmup", "local-test-model")
                if code != 0:
                    result.update(status="NOT_RUN", reason=f"warmup exit={code}")
                    break
        if result["status"] == "PASS":
            # First pass: independent workspaces provide the performance sample.
            for _ in range(args.runs):
                with tempfile.TemporaryDirectory() as directory:
                    workspace = Path(directory) / "workspace"
                    code, elapsed, _, _ = run(command, config, workspace, "local-test-model")
                    if code != 0:
                        result["initialization"]["failures"] += 1
                        result.update(status="FAIL", reason=f"init exit={code}")
                        break
                    result["initialization"]["success"] += 1
                    timings.append(elapsed)
                    main = workspace / "agents/main/AGENT.md"
                    text = main.read_text(encoding="utf-8")
                    if "name: primary" not in text or "tools: []" not in text:
                        result.update(status="FAIL", reason="generated main missing primary/tools")
                        break
            if len(timings) == args.runs:
                result["timing_seconds"] = {
                    "p50": percentile(timings, 0.50),
                    "p95": percentile(timings, 0.95),
                    "max": max(timings),
                    "under_2_seconds": sum(value < 2 for value in timings),
                }
                result["performance_pass"] = result["timing_seconds"]["under_2_seconds"] >= args.runs - 1
                if not result["performance_pass"]:
                    result.update(status="FAIL", reason="fewer than runs-1 initializations under 2 seconds")

            # Idempotency pass: rerunning init must leave the existing main byte-for-byte intact.
            if result["status"] == "PASS":
                with tempfile.TemporaryDirectory() as directory:
                    workspace = Path(directory) / "workspace"
                    code, _, _, _ = run(command, config, workspace, "local-test-model")
                    if code != 0:
                        result["idempotency"]["failures"] += 1
                        result.update(status="FAIL", reason=f"idempotency seed exit={code}")
                    else:
                        main = workspace / "agents/main/AGENT.md"
                        before = main.read_bytes()
                        for _ in range(args.runs):
                            code, _, _, _ = run(command, config, workspace, "another-model")
                            result["idempotency"]["runs"] += 1
                            if code != 0:
                                result["idempotency"]["failures"] += 1
                                result.update(status="FAIL", reason=f"repeat init exit={code}")
                                break
                            if main.read_bytes() != before:
                                result["idempotency"]["content_changes"] += 1
                                result.update(status="FAIL", reason="main changed on repeat init")
                                break

            # Unknown-provider pass: every attempt must fail before creating its workspace.
            if result["status"] == "PASS":
                for _ in range(args.runs):
                    with tempfile.TemporaryDirectory() as directory:
                        workspace = Path(directory) / "unknown-provider"
                        code, _, _, _ = run(
                            command, config, workspace, "local-test-model", "missing-provider"
                        )
                        result["unknown_provider"]["runs"] += 1
                        if code == 2:
                            result["unknown_provider"]["exit_2"] += 1
                        else:
                            result["unknown_provider"]["failures"] += 1
                            result.update(status="FAIL", reason=f"unknown provider exit={code}")
                            break
                        if workspace.exists():
                            result["unknown_provider"]["writes"] += 1
                            result.update(status="FAIL", reason="unknown provider wrote workspace")
                            break
                result["unknown_provider"]["pass"] = (
                    result["unknown_provider"]["exit_2"] == args.runs
                    and result["unknown_provider"]["writes"] == 0
                )
            result["idempotency"]["pass"] = (
                result["idempotency"]["runs"] == args.runs
                and result["idempotency"]["content_changes"] == 0
                and result["idempotency"]["failures"] == 0
            )
            result["initialization"]["pass"] = (
                result["initialization"]["success"] == args.runs
                and result["initialization"]["failures"] == 0
            )
    finally:
        if config_temp is not None:
            Path(config_temp.name).unlink(missing_ok=True)

    if args.markdown:
        print(f"| C2 | {result['status']} | runs={result.get('runs')} |")
        if "timing_seconds" in result:
            print(f"| timing | p50={result['timing_seconds']['p50']:.3f}s p95={result['timing_seconds']['p95']:.3f}s max={result['timing_seconds']['max']:.3f}s |")
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("PASS", "NOT_RUN") else 1


if __name__ == "__main__":
    raise SystemExit(main())
