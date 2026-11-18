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


def check_tool_evidence(root):
    for (module, name), methods in TOOL_EVIDENCE.items():
        report = root / module / "target/surefire-reports" / f"TEST-{name}.xml"
        try:
            suite = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            print(f"Tool 数据库证据报告缺失或无法解析：{name}", file=sys.stderr)
            return False
        for method in methods:
            cases = [case for case in suite.iter("testcase")
                     if case.get("classname") == name and case.get("name") == method]
            if len(cases) != 1 or any(cases[0].find(tag) is not None
                                     for tag in ("skipped", "failure", "error")):
                print(f"Tool 数据库证据未成功执行：{name}.{method}", file=sys.stderr)
                return False
    print("Tool 数据库证据已执行并通过（2 个测试类、7 个关键用例）。")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="?", type=Path,
                        default=Path("AgentOS-storage/target/surefire-reports"))
    parser.add_argument("--tools", action="store_true", help="同时核对 Tool 数据库验收证据")
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
    if args.tools and not check_tool_evidence(reports.resolve().parents[2]):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
