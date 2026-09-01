#!/usr/bin/env python3
"""W8 商旅场景回归 runner（docs/design/architecture/20260901-w8-trip-scenario.md §3.2）
用法: python scripts/eval/trip_runner.py [--json scripts/eval/trip-tasks.json] [--base http://localhost:8082]
流程: 启动 trip_booking → 轮询至 WAITING_APPROVAL → 自动审批通过 → 轮询终态 → 统计成功率
依赖: 本机已启动 agent-platform(8082)。LLM 节点需可用通道，否则任务在 n3 失败（报告为准）。
"""
import argparse
import json
import sys
import time
import urllib.request

TERMINAL = {"COMPLETED", "FAILED", "CANCELED", "REJECTED"}


def api(base, path, method="GET", body=None):
    url = base + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode())


def poll_status(base, instance_id, timeout_s=180):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        d = api(base, f"/api/workflow/instances/{instance_id}")
        st = d["data"]["status"]
        if st in TERMINAL or st == "WAITING_APPROVAL":
            return st, d["data"].get("errorMsg")
        time.sleep(3)
    return "TIMEOUT", None


def run_task(base, task):
    tid = task["taskId"]
    r = api(base, "/api/workflow/instances", "POST", {
        "flowRef": "trip_booking", "appId": "TR_BOOKING", "input": task["input"]})
    iid = r["data"]

    st, err = poll_status(base, iid)
    if st == "WAITING_APPROVAL":
        nodes = api(base, f"/api/workflow/instances/{iid}/nodes")["data"]
        human = [n for n in nodes if n["nodeType"] == "HUMAN" and n["status"] == "WAITING_APPROVAL"]
        if human:
            api(base, f"/api/workflow/instances/{iid}/approval", "POST",
                {"nodeRunId": human[-1]["nodeRunId"], "approved": True, "comment": "回归自动审批"})
            st, err = poll_status(base, iid)
    return tid, st, (err or "")[:160]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default="scripts/eval/trip-tasks.json")
    ap.add_argument("--base", default="http://localhost:8082")
    args = ap.parse_args()

    with open(args.json, encoding="utf-8") as f:
        tasks = json.load(f)

    results = []
    for t in tasks:
        tid, st, err = run_task(args.base, t)
        # 正确性判定（W8 口径）：合规任务→COMPLETED；违规任务→FAILED 且 errorMsg 含 POLICY_VIOLATION（100% 拦截）
        exp = t["expect"].get("compliant", True)
        if exp:
            ok = st == "COMPLETED"
        else:
            ok = st == "FAILED" and "POLICY_VIOLATION" in (err or "")
        results.append((tid, st, err, ok))
        print(f"{tid}: {st}  {'OK' if ok else 'X'}  {err}")

    total = len(results)
    ok = sum(1 for *_, o in results if o)
    rate = ok / total * 100 if total else 0
    compiled = sum(1 for _, st, *_ in results if st == "COMPLETED")
    violations = [t for t in tasks if not t["expect"].get("compliant", True)]
    blocked = [r for r in results if r[1] == "FAILED" and "POLICY_VIOLATION" in (r[2] or "")]
    print(f"\n===== 正确率 {ok}/{total} = {rate:.1f}% "
          f"(合规完成 {compiled}/{total - len(violations)} · 违规拦截 {len(blocked)}/{len(violations)}) =====")
    return 0 if rate >= 80 else 1


if __name__ == "__main__":
    sys.exit(main())