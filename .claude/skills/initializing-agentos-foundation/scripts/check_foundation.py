#!/usr/bin/env python3
"""检查 AgentOS 工程基线是否铺齐。只读，不改任何文件。

用法（在仓库根目录运行，按当前目录检查；脚本本身可以放在任何位置）：
    python3 <skill>/scripts/check_foundation.py
    python3 <skill>/scripts/check_foundation.py --mvn   # 额外跑 mvn verify
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path.cwd()

FILES = [
    "config/checkstyle/checkstyle.xml",
    "config/spotbugs/spotbugs-exclude.xml",
    ".gitleaks.toml",
    ".github/dependabot.yml",
    ".github/workflows/ci.yml",
    ".github/workflows/security.yml",
    "AgentOS-core/src/main/java/com/agentos/core/error/ErrorCode.java",
    "AgentOS-core/src/main/java/com/agentos/core/error/AgentOSException.java",
    "AgentOS-web/src/main/java/com/agentos/web/api/ApiResponse.java",
    "AgentOS-web/src/main/java/com/agentos/web/api/GlobalExceptionHandler.java",
    "AgentOS-web/src/main/java/com/agentos/web/config/WebConfig.java",
    "AgentOS-web/src/test/java/com/agentos/web/api/GlobalExceptionHandlerTest.java",
    "AgentOS-boot/src/test/java/com/agentos/boot/ArchitectureRulesTest.java",
    "AgentOS-boot/src/test/java/com/agentos/boot/LoggingBaselineTest.java",
]

# (文件, 必须出现的文本, 说明)
CONTAINS = [
    ("pom.xml", "maven-enforcer-plugin", "根 pom 有依赖禁令"),
    ("pom.xml", "spotless-maven-plugin", "根 pom 有格式化检查"),
    ("pom.xml", "maven-checkstyle-plugin", "根 pom 有 Checkstyle"),
    ("pom.xml", "findsecbugs-plugin", "根 pom 有 SpotBugs + FindSecBugs"),
    ("pom.xml", "spring-boot-starter-test", "所有模块可写测试"),
    ("AgentOS-boot/pom.xml", "spring-boot-starter-actuator", "boot 引入 Actuator"),
    ("AgentOS-boot/pom.xml", "<artifactId>archunit</artifactId>", "boot 引入 ArchUnit"),
    ("AgentOS-boot/src/main/resources/application.yaml", "format:\n      file: ecs", "文件日志为 ECS JSON"),
    ("AgentOS-boot/src/main/resources/application.yaml", "shutdown: graceful", "HTTP 优雅停机"),
    ("AgentOS-boot/src/main/resources/application.yaml", "include: health", "Actuator 只暴露 health"),
    ("AgentOS-boot/src/main/resources/application.yaml", "include-content: false", "工具内容不导出到 Span"),
    (".gitignore", ".AgentOS/logs/", "运行日志不进 git"),
    ("CLAUDE.md", "## 工程基线", "CLAUDE.md 有工程基线说明"),
]

# 只看 <dependency> 块，enforcer 的 <exclude> 禁令清单本身不算命中
DEPENDENCY = re.compile(r"<dependency>.*?</dependency>", re.S)
BANNED = re.compile(r"<groupId>(com\.alibaba\.cloud\.ai|com\.h2database)</groupId>|<artifactId>sqlite-jdbc</artifactId>")


def main() -> int:
    failures = 0

    def report(ok: bool, text: str) -> None:
        nonlocal failures
        print(("  ✓ " if ok else "  ✗ ") + text)
        failures += 0 if ok else 1

    print("文件：")
    for f in FILES:
        report((ROOT / f).is_file(), f)

    print("配置：")
    for f, needle, desc in CONTAINS:
        path = ROOT / f
        report(path.is_file() and needle in path.read_text(encoding="utf-8"), f"{desc}（{f}）")

    print("禁用依赖：")
    hits = [str(p.relative_to(ROOT)) for p in ROOT.glob("**/pom.xml")
            if "target" not in p.parts
            and any(BANNED.search(d) for d in DEPENDENCY.findall(p.read_text(encoding="utf-8")))]
    report(not hits, "pom 里没有 alibaba / H2 / SQLite" + (f"，命中：{hits}" if hits else ""))

    if "--mvn" in sys.argv:
        print("mvn -B -q verify：")
        report(subprocess.run(["mvn", "-B", "-q", "verify"], cwd=ROOT).returncode == 0, "质量门通过")

    print(f"\n{'全部通过' if failures == 0 else f'{failures} 项未通过'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
