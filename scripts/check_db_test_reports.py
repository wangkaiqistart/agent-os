"""确认 CI 真的执行了 PostgreSQL 连通性测试，缺失或跳过均返回失败。"""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


CLASS_NAME = "com.agentos.storage.support.PostgresConnectivityTest"
METHOD_NAME = "connectsUsingContainerProperties"


def main():
    reports = Path(sys.argv[1]) if len(sys.argv) == 2 else Path(
        "AgentOS-storage/target/surefire-reports"
    )
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
