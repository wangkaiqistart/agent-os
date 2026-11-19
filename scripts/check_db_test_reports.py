"""确认 CI 真的执行了 PostgreSQL 连通性测试，缺失或跳过均返回失败。"""

from pathlib import Path
import sys
import argparse
import xml.etree.ElementTree as ET


CLASS_NAME = "com.agentos.storage.support.PostgresConnectivityTest"
METHOD_NAME = "connectsUsingContainerProperties"


# Tool 首期验收的数据库证据索引；必须有具体方法执行，空测试套件不能替代。
TOOL_EVIDENCE = {
    ("AgentOS-storage", "com.agentos.storage.audit.JpaToolInvocationRecorderTest"): (
        "beginThenCompleteUpdatesSameRowAndPreservesDefaultsAndJson",
        "failedCompletionMarksOriginalIntentUnconfirmedWithoutReplayingTool",
    ),
    ("AgentOS-boot", "com.agentos.boot.ReActDeadlineIntegrationTest"): (
        "retryAndNextIterationShareActualAttemptBudget",
        "sameResponseAndLaterIterationRespectOfficialPerToolLimit",
        "slowBodyStopsTurnWithoutNextModelOrHttpRetry",
        "oversizedBodyReturnsSafeToolErrorAndContinuesWithOneHttpRequest",
        "completionFailurePersistsUnconfirmedAndNeverReplaysSideEffect",
    ),
}


# 005完整集成矩阵；每类必须是本轮真实PostgreSQL用例，保留004关键方法检查。
CAPABILITY_CLASSES = (
    "ToolSnapshotBeforeTurnIntegrationTest", "PluginToolAcceptanceIntegrationTest",
    "FileToolsAcceptanceIntegrationTest", "ShellToolsAcceptanceIntegrationTest",
    "McpToolAcceptanceIntegrationTest", "HttpPostAcceptanceIntegrationTest",
    "FileSearchAcceptanceIntegrationTest", "AskUserAcceptanceIntegrationTest",
    "WebSearchAcceptanceIntegrationTest", "WebSearchUnconfiguredAcceptanceIntegrationTest",
    "ToolCapabilityMatrixIntegrationTest", "ToolCapabilitiesDeadlineIntegrationTest",
    "ToolCapabilitiesPrivacyIntegrationTest",
)


def successful_suite(report, name, since):
    try:
        suite = ET.parse(report).getroot()
        cases = [c for c in suite.iter("testcase") if c.get("classname") == name]
        if report.stat().st_mtime < since or not cases or int(suite.get("tests", 0)) != len(cases):
            raise ValueError()
        if any(int(suite.get(k, 0)) for k in ("failures", "errors", "skipped")):
            raise ValueError()
        if any(c.find(tag) is not None for c in cases for tag in ("skipped", "failure", "error")):
            raise ValueError()
    except (ET.ParseError, OSError, ValueError):
        print(f"数据库证据缺失、过期或未成功执行：{name}", file=sys.stderr)
        return None
    return suite


def check_tool_evidence(root, since):
    for (module, name), methods in TOOL_EVIDENCE.items():
        report = root / module / "target/surefire-reports" / f"TEST-{name}.xml"
        suite = successful_suite(report, name, since)
        if suite is None:
            return False
        for method in methods:
            cases = [case for case in suite.iter("testcase")
                     if case.get("classname") == name and case.get("name") == method]
            if len(cases) != 1 or any(cases[0].find(tag) is not None
                                     for tag in ("skipped", "failure", "error")):
                print(f"Tool 数据库证据未成功执行：{name}.{method}", file=sys.stderr)
                return False
    for short_name in CAPABILITY_CLASSES:
        name = "com.agentos.boot." + short_name
        report = root / "AgentOS-boot/target/surefire-reports" / f"TEST-{name}.xml"
        suite = successful_suite(report, name, since)
        if suite is None:
            return False
        output = "".join(suite.itertext())
        if "Database version: 17." not in output or "pgvector/pgvector:pg17" not in output:
            print(f"005 PostgreSQL运行证据缺失：{name}", file=sys.stderr)
            return False
    print(f"Tool 数据库证据已执行并通过（004的2类/7方法，005的{len(CAPABILITY_CLASSES)}类）。")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="?", type=Path,
                        default=Path("AgentOS-storage/target/surefire-reports"))
    parser.add_argument("--tools", action="store_true", help="同时核对 Tool 数据库验收证据")
    parser.add_argument("--since", type=float, default=None,
                        help="本轮verify开始的Unix秒；--tools未给出时使用根target/verify-started-at")
    args = parser.parse_args()
    reports = args.reports
    report = reports / f"TEST-{CLASS_NAME}.xml"
    if not report.is_file():
        print("数据库连通性报告缺失，CI 不允许未运行 db 测试。", file=sys.stderr)
        return 1
    try:
        suite = ET.parse(report).getroot()
    except (ET.ParseError, OSError):
        # 报告可能含连接详情，不把原始内容或解析异常打印到 CI。
        print("数据库连通性报告无法解析。", file=sys.stderr)
        return 1
    cases = [
        case for case in suite.iter("testcase")
        if case.get("classname") == CLASS_NAME and case.get("name") == METHOD_NAME
    ]
    if len(cases) != 1 or any(
        cases[0].find(tag) is not None for tag in ("skipped", "failure", "error")
    ):
        print("数据库连通性测试未成功执行，缺失、跳过或失败均不允许。", file=sys.stderr)
        return 1
    print("数据库连通性测试已执行并通过。")
    if args.tools:
        root = reports.resolve().parents[2]
        try:
            since = args.since if args.since is not None else float((root / "target/verify-started-at").read_text())
        except (OSError, ValueError):
            print("缺少本轮verify起始时间；先记录target/verify-started-at或传--since。", file=sys.stderr)
            return 1
        if successful_suite(report, CLASS_NAME, since) is None or not check_tool_evidence(root, since):
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
