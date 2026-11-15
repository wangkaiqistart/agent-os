"""CI 数据库报告检查的离线回归测试，不启动 Docker。"""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check_db_test_reports.py"
CLASS_NAME = "com.agentos.storage.support.PostgresConnectivityTest"
METHOD_NAME = "connectsUsingContainerProperties"


class CheckDatabaseReportsTest(unittest.TestCase):
    def run_check(self, report=None):
        with tempfile.TemporaryDirectory() as directory:
            if report is not None:
                path = Path(directory) / f"TEST-{CLASS_NAME}.xml"
                path.write_text(report, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(SCRIPT), directory],
                check=False,
                capture_output=True,
                text=True,
            )

    def report(self, child="", method=METHOD_NAME):
        return (
            f'<testsuite name="{CLASS_NAME}" tests="1">'
            f'<testcase classname="{CLASS_NAME}" name="{method}">'
            f"{child}</testcase></testsuite>"
        )

    def test_successful_connectivity_must_be_executed(self):
        self.assertEqual(self.run_check(self.report()).returncode, 0)

    def test_missing_report_fails(self):
        self.assertNotEqual(self.run_check().returncode, 0)

    def test_skipped_connectivity_fails(self):
        self.assertNotEqual(self.run_check(self.report("<skipped/>")).returncode, 0)

    def test_failure_or_error_fails(self):
        for child in ("<failure/>", "<error/>"):
            with self.subTest(child=child):
                self.assertNotEqual(self.run_check(self.report(child)).returncode, 0)

    def test_zero_tests_fails(self):
        report = f'<testsuite name="{CLASS_NAME}" tests="0"/>'
        self.assertNotEqual(self.run_check(report).returncode, 0)

    def test_unrelated_test_cannot_replace_connectivity(self):
        self.assertNotEqual(self.run_check(self.report(method="environmentBranch")).returncode, 0)

    def test_malformed_xml_fails_without_printing_report(self):
        result = self.run_check("<SECRET-REPORT")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("SECRET-REPORT", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
