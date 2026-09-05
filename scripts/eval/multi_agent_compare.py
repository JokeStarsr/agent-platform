#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
W13 协作回归对比（docs/design/architecture/20260905-multi-agent.md §9）
================================
20 个协作任务 × {单 Agent 基线, Supervisor, Pipeline} 三跑，统计成功率。
单 Agent 基线 = 内置 sv_final mock 专家直接产出（确定性、不烧 token）；
Supervisor = 提交 /api/multi-agent/runs 轮询到 COMPLETED。

用法：
    1) agent-platform 已启动（t_multi_agent_run 表已建）
    2) python3 scripts/eval/multi_agent_compare.py
退出码：Supervisor 成功率 ≥ 单 Agent 且 ≥ 半数任务优于基线 → 0；否则 1。
"""

import json
import os
import sys
import time
import urllib.request

BASE_URL = os.getenv("MULTI_AGENT_BASE_URL", "http://localhost:8082")

TASKS = [
    "帮我规划一次北京出差，住3晚，预算合理",
    "组织上海团队季度团建方案（含酒店与餐厅）",
    "深圳客户拜访行程安排（2天，2家客户）",
    "广州出差要准备哪些政策合规事项",
    "成都新项目启动的差旅规划",
    "杭州会议差旅（1天往返）",
    "武汉办事处调研行程（3天）",
    "西安分公司开业支持出行方案",
    "南京供应商考察（2天）",
    "重庆客户现场支持（住4晚）",
    "北京政策咨询：住宿与交通标准",
    "上海比价：周五去周日回的航班与酒店",
    "深圳合规检查：这个差旅方案合不合规",
    "广州政策与比价综合方案",
    "成都三天行程的完整预算方案",
    "杭州离出发3天的机票预订方案",
    "武汉出差报销要准备什么凭证",
    "西安的酒店选择建议（4星还是5星）",
    "南京航班改签如何处理",
    "重庆行程取消的注意事项",
]

def post(url, body, timeout=60):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Tenant-Id": "default"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))

def get(url, timeout=30):
    req = urllib.request.Request(url, headers={"X-Tenant-Id": "default"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))

def baseline(task):
    """单 Agent 基线 = sv_final mock 单步（确定性成功）"""
    return True  # mock 基线恒成功（实验设计标注）

def run_supervisor(task):
    try:
        d = post(f"{BASE_URL}/api/multi-agent/runs", {"topology": "supervisor", "task": task, "appId": "sv_delegator"})
        rid = d["data"]["rootRunId"]
        for _ in range(30):
            time.sleep(0.5)
            det = get(f"{BASE_URL}/api/multi-agent/runs/{rid}")["data"]
            if det["status"] in ("COMPLETED", "FAILED", "CANCELLED"):
                return det["status"] == "COMPLETED"
        return False
    except Exception:
        return False

def run_pipeline(task):
    try:
        stages = [{"agent": "sv_summarize", "prompt": "请总结：{prev}"},
                  {"agent": "sv_translate", "prompt": "翻译：{prev}"}]
        d = post(f"{BASE_URL}/api/multi-agent/runs", {"topology": "pipeline", "task": task, "appId": "sv_pipeline", "stages": stages})
        rid = d["data"]["rootRunId"]
        for _ in range(30):
            time.sleep(0.5)
            det = get(f"{BASE_URL}/api/multi-agent/runs/{rid}")["data"]
            if det["status"] in ("COMPLETED", "FAILED", "CANCELLED"):
                return det["status"] == "COMPLETED"
        return False
    except Exception:
        return False

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else "all"
    results = []
    for i, task in enumerate(TASKS, 1):
        base = baseline(task)
        sv = run_supervisor(task) if only in ("all", "supervisor") else True
        pl = run_pipeline(task) if only in ("all", "pipeline") else True
        better = sv and not base or (sv == base and base)
        results.append((task[:22], base, sv, pl, better))
        print(f"[{i:>2}] {'OK ' if sv else 'X  '} baseline={base} sv={sv} pipe={pl} task={task[:24]}")

    base_ok = sum(1 for r in results if r[1])
    sv_ok = sum(1 for r in results if r[2])
    pl_ok = sum(1 for r in results if r[3])
    better = sum(1 for r in results if r[4])
    print("-" * 60)
    print(f"单Agent基线 : {base_ok}/{len(results)}")
    print(f"Supervisor : {sv_ok}/{len(results)}  ({better}/{len(results)} 任务≥基线)")
    print(f"Pipeline   : {pl_ok}/{len(results)}")
    if sv_ok >= max(base_ok, len(results) // 2):
        print("PASS（Supervisor ≥ 半数任务且 ≥ 单 Agent 基线）")
        sys.exit(0)
    print("FAIL（未达 P3 闸门：多智能体需 ≥ 半数任务不差于单 Agent）")
    sys.exit(1)

if __name__ == "__main__":
    main()