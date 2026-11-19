#!/usr/bin/env python3
"""重放三个离线真实Manager/PostgreSQL Demo，汇总安全证据；不代替llm评估。"""
import argparse
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--reuse-report', action='store_true', help='只汇总已有报告，默认重新运行')
args = parser.parse_args()
if not args.reuse_report:
    with (root / 'target/demo-replay.log').open('w') as log:
        subprocess.run(['mvn', '-B', '-ntp', '-pl', 'AgentOS-boot', '-am',
                        '-Dtest=ToolSummaryDemoIntegrationTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'],
                       cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
data = json.loads((root / 'AgentOS-boot/target/demo-summary-results.json').read_text())
assert set(data) == {'weather', 'news', 'github'}
for demo in data.values():
    assert demo['status'] == 'PASS'
    assert demo['mode'] == 'scripted-local-model'
    assert all(row['status'] == 'success' for row in demo['audits'])
    assert set(demo['eventIds']) == {row['tool_call_id'] for row in demo['audits']}
args.output.mkdir(parents=True, exist_ok=True)
(args.output / 'demo-summary-results.json').write_text(json.dumps(data, ensure_ascii=False, indent=2))
print('三个离线Demo：PASS；真实模型/官方评估结果请单独检查。')
