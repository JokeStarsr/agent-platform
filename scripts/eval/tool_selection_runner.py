#!/usr/bin/env python3
"""
工具选择准确率评测 runner（docs/design/architecture/20260905-tool-marketplace.md §7.2）
================================
读取 tool-selection.json（50 条任务 → 期望工具）→ 构造"工具清单 + 任务描述"提示词 →
调用平台 /api/chat/ask 让模型返回应选工具名 → 与 expectedTool 比对 → 输出准确率报告。

使用前准备：
1. agent-platform 已启动 (8082)
2. ToolRegistry 已加载 ≥10 工具（/api/tool-market/runtime 可查）
3. 模型路由正常（默认 zen / aliyun 兜底）

Usage:
    python3 tool_selection_runner.py --json ./tool-selection.json

退出码：准确率 ≥ 90%（可 GATED_ACCURACY 覆盖）→ 0；否则 1（供 CI 阻断）。
"""

import json
import os
import re
import sys
import urllib.request

BASE_URL = os.getenv("GOLDEN_SET_BASE_URL", "http://localhost:8082")
DEFAULT_GATE = 0.90
GATE = float(os.getenv("TOOL_SELECTION_GATE", str(DEFAULT_GATE)))

SELECT_PROMPT = """你是工具路由判定器。下面列出当前平台可用的全部工具（name: description）。

{tool_list}

用户任务如下。请选出【最合适的唯一】工具，只输出工具名（小写下划线），不要解释、不要加引号、不要输出其他文字。

任务：{task}
"""


def _load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def collect_expected(items):
    """以 expectedTool 为准 -> 也收集 falseTools 干扰项（若存在）"""
    exp = [it["expectedTool"] for it in items]
    return set(exp)


def _fetch_json(url, payload):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Tenant-Id": "default"},
    )
    with urllib.request.urlopen(req, timeout=90) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _ask(prompt, model=None):
    body = {"message": prompt}
    if model:
        body["model"] = model
    data = _fetch_json(f"{BASE_URL}/api/chat/ask", body)
    return data.get("data", "") if isinstance(data, dict) else str(data)


def _extract_tool(text, known_tools):
    """在返回文本中找出现的工具名（最长匹配优先，防子串误配）"""
    if not text:
        return None
    for tool in sorted(known_tools, key=len, reverse=True):
        if re.search(rf"\b{tool}\b", text, re.IGNORECASE):
            return tool
    # 兜底：清洗后全串可能是工具名
    cleaned = re.sub(r"[^a-z0-9_]", "", text.lower())
    if cleaned in known_tools:
        return cleaned
    return None


def fetch_tool_catalog():
    """从平台目录拉取已发布工具（含描述），构建 with 描述的过滤集。
    失败时回退到评测集内工具名集合。"""
    try:
        url = f"{BASE_URL}/api/tool-market/runtime"
        req = urllib.request.Request(url, headers={"X-Tenant-Id": "default"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        tools = data.get("data", [])
        if tools:
            return {t["name"]: (t.get("description") or t["name"]) for t in tools}
    except Exception as e:
        print(f"  !! 目录拉取失败，回退评测集内工具: {e}")
    return {}


def evaluate(items):
    """已知工具全集合（含 all expected + falseTools）；
    优先用平台目录的真实工具+描述，目录不可用回退 name-only。"""
    catalog = fetch_tool_catalog()
    known = set()
    for it in items:
        known.add(it["expectedTool"])
        for ft in it.get("falseTools", []):
            known.add(ft)

    # 工具清单（目录有描述则带描述；否则仅 name）
    tool_lines = []
    for t in sorted(known):
        if t in catalog:
            tool_lines.append(f"- {t}: {catalog[t]}")
        else:
            tool_lines.append(f"- {t}")
    tool_list = "\n".join(tool_lines)

    results = []
    for i, it in enumerate(items, 1):
        prompt = SELECT_PROMPT.format(tool_list=tool_list, task=it["task"])
        text = _ask(prompt)
        picked = _extract_tool(text or "", known)
        ok = picked == it["expectedTool"]
        results.append({
            "task": it["task"][:40],
            "expected": it["expectedTool"],
            "picked": picked,
            "raw": (text or "<empty>")[:40],
            "ok": ok,
        })
        flag = "OK " if ok else "X  "
        print(f"[{i:>2}] {flag} expect={it['expectedTool']:<20} picked={str(picked):<20} task={it['task'][:30]}")
        if not ok:
            print(f"       raw: {(text or '<empty>')[:80]!r}")
    return results


def main():
    args = sys.argv[1:]
    path = None
    for i, a in enumerate(args):
        if a == "--json" and i + 1 < len(args):
            path = args[i + 1]
    if not path:
        print("用法: tool_selection_runner.py --json <tool-selection.json>")
        sys.exit(2)

    items = _load(path)
    results = evaluate(items)
    ok = sum(1 for r in results if r["ok"])
    total = len(results)
    acc = ok / total if total else 0.0

    print("\n===== 工具选择准确率报告 =====")
    print(f"条目: {total}")
    print(f"正确: {ok}")
    print(f"准确率: {acc:.3f} (阈值 {GATE:.2f})")
    failed = [r for r in results if not r["ok"]]
    if failed:
        print("未命中明细：")
        for r in failed:
            print(f"  - expect={r['expected']} picked={r['picked']} task={r['task']}")

    if acc >= GATE:
        print("PASS")
        sys.exit(0)
    print("FAIL（低于阈值，阻断合并）")
    sys.exit(1)


if __name__ == "__main__":
    main()