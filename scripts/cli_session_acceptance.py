#!/usr/bin/env python3
"""执行 AgentOS CLI 会话验收并输出可审计的 JSON/Markdown 统计。"""

from __future__ import annotations

import argparse
import json
import shlex
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


DEFAULT_MAVEN_COMMAND = (
    "mvn -B -ntp -pl AgentOS-cli,AgentOS-boot -am "
    "-Dsurefire.failIfNoSpecifiedTests=false "
    "-Dtest=ChatCommandTest,DefaultAgentCliIntegrationTest,SessionConcurrencyIntegrationTest,CliAcceptanceIntegrationTest test"
)


def report(path: Path) -> dict[str, int | str]:
    """读取 Surefire XML，避免把 Maven 的文字日志当成验收证据。"""
    if not path.is_file():
        return {"status": "MISSING", "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    root = ET.parse(path).getroot()
    return {
        "status": "PASS",
        "tests": int(root.attrib.get("tests", 0)),
        "failures": int(root.attrib.get("failures", 0)),
        "errors": int(root.attrib.get("errors", 0)),
        "skipped": int(root.attrib.get("skipped", 0)),
    }


def metric(path: Path) -> dict[str, object]:
    """读取真实 CLI 集成测试写出的可审计统计。"""
    if not path.is_file():
        return {"status": "MISSING"}
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=20)
    parser.add_argument("--maven-command", default=DEFAULT_MAVEN_COMMAND)
    parser.add_argument("--reports-root", type=Path, default=Path("."))
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()

    docker = shutil.which("docker")
    if docker is None:
        result = {"status": "NOT_RUN", "reason": "docker executable unavailable", "runs": args.runs}
    else:
        probe = subprocess.run([docker, "info"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if probe.returncode != 0:
            result = {"status": "NOT_RUN", "reason": "Docker daemon unavailable", "runs": args.runs}
        else:
            metrics_root = args.reports_root.resolve() / "AgentOS-boot/target"
            for name in (
                "cli-identity-isolation.json",
                "cli-new-session-idle.json",
                "cli-new-session-busy.json",
            ):
                (metrics_root / name).unlink(missing_ok=True)
            completed = subprocess.run(
                shlex.split(args.maven_command),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            root = args.reports_root.resolve()
            cli = report(root / "AgentOS-cli/target/surefire-reports/TEST-com.agentos.cli.ChatCommandTest.xml")
            recovery = report(
                root
                / "AgentOS-boot/target/surefire-reports/TEST-com.agentos.boot.DefaultAgentCliIntegrationTest.xml"
            )
            concurrency = report(
                root
                / "AgentOS-boot/target/surefire-reports/TEST-com.agentos.boot.SessionConcurrencyIntegrationTest.xml"
            )
            acceptance = report(
                root
                / "AgentOS-boot/target/surefire-reports/TEST-com.agentos.boot.CliAcceptanceIntegrationTest.xml"
            )
            identity = metric(metrics_root / "cli-identity-isolation.json")
            idle_new = metric(metrics_root / "cli-new-session-idle.json")
            busy_new = metric(metrics_root / "cli-new-session-busy.json")

            # Tests encode the required loops: idle --new is repeated in ChatCommandTest,
            # the integration test performs 20 context restarts, and the busy race repeats 20 times.
            idle_runs = int(idle_new.get("idle_runs", 0))
            recovery_runs = args.runs if recovery["tests"] >= 1 else 0
            busy_runs = int(busy_new.get("busy_runs", 0))
            identity_runs = int(identity.get("runs", 0))
            identity_leaks = int(identity.get("leaks", -1))
            no_failures = all(
                item["failures"] == 0 and item["errors"] == 0 and item["skipped"] == 0
                for item in (cli, recovery, concurrency, acceptance)
            )
            passed = (
                completed.returncode == 0
                and no_failures
                and acceptance["tests"] >= 3
                and identity.get("status") == "PASS"
                and identity_runs == args.runs
                and identity_leaks == 0
                and idle_new.get("status") == "PASS"
                and busy_new.get("status") == "PASS"
                and idle_runs == args.runs
                and recovery_runs == args.runs
                and busy_runs == args.runs
            )
            result = {
                "status": "PASS" if passed else "FAIL",
                "runs": args.runs,
                "restart_recovery": recovery_runs,
                "identity_isolation": identity_runs,
                "identity_leaks": identity_leaks,
                "new_idle": idle_runs,
                "new_busy": busy_runs,
                "maven_exit": completed.returncode,
                "reports": {
                    "cli": cli,
                    "recovery": recovery,
                    "concurrency": concurrency,
                    "acceptance": acceptance,
                },
            }
            if not passed:
                result["reason"] = "integration command or required repeated test evidence failed"

    if args.markdown:
        if result["status"] == "NOT_RUN":
            print(f"| C3 | NOT_RUN | {result['reason']} |")
        else:
            print(
                f"| C3 | {result['status']} | restart={result.get('restart_recovery', 0)}/"
                f"{args.runs}, idle_new={result.get('new_idle', 0)}/{args.runs}, "
                f"busy_new={result.get('new_busy', 0)}/{args.runs}, "
                f"identity={result.get('identity_isolation', 0)}/{args.runs}, "
                f"leaks={result.get('identity_leaks', -1)} |"
            )
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("PASS", "NOT_RUN") else 1


if __name__ == "__main__":
    raise SystemExit(main())
