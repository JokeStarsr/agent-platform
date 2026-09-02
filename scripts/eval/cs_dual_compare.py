#!/usr/bin/env python3
"""
客服迁移双跑对比脚本（W09 P1 二期 · docs/design/api/20260902-app-factory.md §2.5/§6）
=================================================================================
同一份评测集跑两条链路并对比行为是否一致：
  A) legacy 链路：/api/rag/search（无 appId，走 yml 兜底硬编码 Prompt）
  B) 应用工厂链路：/api/rag/search（appId=cs_customer_service，走 t_app 配置 Prompt/handoff）

前置：
  1. agent-platform 已启动（默认 8082，可用 GOLDEN_SET_BASE_URL 覆盖）
  2. 应用表已含 cs_customer_service（种子注册或管理 API 创建）

用法：
  python3 scripts\\eval\\cs_dual_compare.py --json src\\test\\resources\\golden-set\\v1\\golden-set.json
  可选：--app cs_customer_service --gate 0.20（handoff 决策翻转率闸门，超出返回非 0）
"""

import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

BASE_URL = os.getenv("GOLDEN_SET_BASE_URL", "http://localhost:8082")


def call_search(query: str, top_k: int, app_id: str | None) -> dict:
    body = {"query": query, "topK": top_k}
    if app_id:
        body["appId"] = app_id
    req = urllib.request.Request(
        f"{BASE_URL}/api/rag/search",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Tenant-Id": "default"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    d = data.get("data") or {}
    return {
        "answer": d.get("answer", ""),
        "citations": d.get("citations") or [],
        "confidence": d.get("confidenceScore", 0.0),
        "needsHandoff": d.get("needsHandoff", False),
        "handoffReason": d.get("handoffReason", "NONE"),
        "latencyMs": d.get("latencyMs", 0),
    }


def norm(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", required=True, help="golden-set.json 路径")
    ap.add_argument("--app", default="cs_customer_service", help="应用 appId（默认 cs_customer_service）")
    ap.add_argument("--gate", type=float, default=0.20, help="handoff 决策翻转率闸门（默认 0.20）")
    ap.add_argument("--cases", type=int, default=0, help="只跑前 N 条（默认全部）")
    args = ap.parse_args()

    data = json.loads(Path(args.json).read_text(encoding="utf-8"))
    cases = data if isinstance(data, list) else data.get("items") or data.get("cases") or []
    if args.cases > 0:
        cases = cases[: args.cases]

    print(f"双跑对比: app={args.app}  用例数={len(cases)}\n")
    print(f"{'#':>3}  {'问题(截断)':<28} {'答案一致':<8} {'转人工同':<8} {'置信Δ(均值)':<12} 差异细节")
    print("-" * 100)

    same_answer = same_handoff = same_citation = 0
    conf_deltas = []
    diffs = []
    for i, c in enumerate(cases, 1):
        q = c.get("question") or c.get("query")
        topk = int(c.get("topK", 5))
        legacy = call_search(q, topk, None)
        app = call_search(q, topk, args.app)
        ans_same = norm(legacy["answer"]) == norm(app["answer"])
        handoff_same = legacy["needsHandoff"] == app["needsHandoff"]
        citation_same = sorted(legacy["citations"]) == sorted(app["citations"])
        conf_delta = app["confidence"] - legacy["confidence"]
        conf_deltas.append(abs(conf_delta))
        same_answer += int(ans_same)
        same_handoff += int(handoff_same)
        same_citation += int(citation_same)
        detail = []
        # 答案字符串一致是强信号；不一致多为 LLM 措辞非确定性，需人工/判分器复核语义
        if not ans_same:
            detail.append("答案不同(措辞)")
        if not citation_same:
            detail.append(f"引用集不同({len(legacy['citations'])}→{len(app['citations'])})")
        if not handoff_same:
            detail.append(f"handoff {legacy['handoffReason']}→{app['handoffReason']}")
        if abs(conf_delta) >= 0.05:
            detail.append(f"置信{legacy['confidence']:.2f}→{app['confidence']:.2f}")
        did = ";".join(detail) or "-"
        diffs.append((i, did))
        print(f"{i:>3}  {q[:26]:<28} {'✓' if ans_same else '✗':<8} {'✓' if handoff_same else '✗':<8}"
              f"{(sum(conf_deltas)/len(conf_deltas)):.4f}  {did}")

    n = len(cases)
    ans_rate = same_answer / n
    handoff_rate = same_handoff / n
    citation_rate = same_citation / n
    avg_conf_delta = sum(conf_deltas) / n
    print("-" * 100)
    print(f"答案字符串一致 : {ans_rate:.0%}（LLM 措辞非确定性；语义等价以引用/转人工/判分器为准）")
    print(f"引用集一致     : {citation_rate:.0%}")
    print(f"转人工决策一致 : {handoff_rate:.0%}")
    print(f"置信度平均漂移 : {avg_conf_delta:.4f}")
    handoff_flip = 1 - handoff_rate
    print(f"handoff 翻转率 : {handoff_flip:.0%}（闸门 ≤{args.gate:.0%}）")

    if handoff_flip > args.gate:
        print(f"\n[FAIL] handoff 决策翻转率 {handoff_flip:.0%} 超过闸门 {args.gate:.0%}，"
              "客服迁移双跑回归不通过")
        return 1
    print(f"\n[PASS] 迁移双跑行为一致（handoff 翻转率在闸门内）")
    return 0


if __name__ == "__main__":
    sys.exit(main())