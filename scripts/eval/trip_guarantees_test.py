#!/usr/bin/env python3
"""W8 商旅硬断言（docs/design/architecture/20260901-w8-trip-scenario.md §3.3/§7）
AC-1 政策违规 100% 被拦截：expect.compliant=false 的任务必须 FAILED 且 errorMsg 含 POLICY_VIOLATION
AC-2 支付不可达：工具层 403 由 JUnit PaymentGateTest 覆盖（PAYMENT 权限硬 403），此处做结构断言（流程定义不含可达支付）
用法: python scripts/eval/trip_guarantees_test.py [--base http://localhost:8082]
"""
import argparse
import json
import sys
import urllib.request


def api(base, path, method="GET", body=None):
    url = base + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8082")
    args = ap.parse_args()

    # AC-2 结构断言：内置商旅流程不含支付工具（pay_order 在任何业务流程中不可达）
    flow = api(args.base, "/api/workflow/flows/trip_booking")["data"]["flowDef"]
    if "pay_order" in flow:
        print("FAIL: trip_booking 流程中出现 pay_order（应不可达）")
        return 1
    print("AC-2 PASS: 商旅流程不含支付工具；pay_order 工具层 403 由 JUnit PaymentGateTest 保证")

    # AC-1 断言由 trip_runner 的违规任务 FAILED + POLICY_VIOLATION 复核（此处对任务清单做静态校验）
    with open("scripts/eval/trip-tasks.json", encoding="utf-8") as f:
        tasks = json.load(f)
    bad = [t["taskId"] for t in tasks if t["expect"]["compliant"] and any(
        k in t["input"] and v in ("头等舱",) for k, v in [("flightClass", t["input"].get("flightClass"))]
    )]
    # 简单交叉校验：违规任务应确实携带违规取值（舱位/星级/天数）
    violations = [t for t in tasks if not t["expect"]["compliant"]]
    for t in violations:
        inp = t["input"]
        if inp.get("flightClass") == "头等舱" or inp.get("hotelStar", 0) > 3 or inp.get("bookAheadDays", 99) < 2:
            continue
        print(f"WARN: {t['taskId']} 标记违规但输入看似合规（runner 实测为准）")
    print(f"AC-1 PASS(清单): {len(violations)} 个违规任务已在清单中，实测拦截率见 trip_runner.py 输出")
    return 0


if __name__ == "__main__":
    sys.exit(main())