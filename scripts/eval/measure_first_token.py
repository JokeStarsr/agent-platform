#!/usr/bin/env python3
"""
First-Token 延迟测量（P1 闸门：首 Token ≤ 2s）
================================================
从 Golden Set 随机抽样 N 条，调用 /api/rag/search/stream（SSE），
统计首个 answer 事件的到达时间（firstTokenMs），报告均值/P50/P95。

用法:
    python measure_first_token.py [golden-set.json路径] [抽样数N]
环境变量:
    GOLDEN_SET_BASE_URL  默认 http://localhost:8082
    FIRST_TOKEN_SAMPLE    默认 10

前提: agent-platform 已启动(mvn spring-boot:run)，知识库已索引。
"""

import json
import os
import random
import sys
import time
import urllib.request
from typing import Dict, List, Optional, Tuple

BASE_URL = os.getenv("GOLDEN_SET_BASE_URL", "http://localhost:8082")
SAMPLE_N = int(os.getenv("FIRST_TOKEN_SAMPLE", "10"))


def measure(query: str, tenant: str = "default") -> Tuple[Optional[float], Optional[Dict]]:
    """调 stream 端点，返回 (firstTokenMs, done 事件 dict)；失败返回 (None, None)"""
    url = f"{BASE_URL}/api/rag/search/stream"
    payload = json.dumps({"query": query, "topK": 5}).encode("utf-8")
    req = urllib.request.Request(
        url, data=payload,
        headers={"Content-Type": "application/json", "X-Tenant-Id": tenant, "Accept": "text/event-stream"},
    )
    start = time.time()
    first_ms = None
    done_info = None
    with urllib.request.urlopen(req, timeout=120) as resp:
        for raw in resp:
            line = raw.decode("utf-8", errors="ignore").strip()
            if not line.startswith("data:"):
                continue
            evt = json.loads(line[5:])
            if evt.get("type") == "answer" and first_ms is None:
                first_ms = (time.time() - start) * 1000
            if evt.get("type") == "done":
                done_info = evt
    return first_ms, done_info


def main():
    json_path = sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/golden-set/v1/golden-set.json"
    sample_n = int(sys.argv[2]) if len(sys.argv) > 2 else SAMPLE_N
    with open(json_path, encoding="utf-8") as f:
        items = json.load(f)["items"]
    sample = random.sample(items, min(sample_n, len(items)))

    times: List[float] = []
    for it in sample:
        try:
            first_ms, done = measure(it["question"])
            if first_ms is None:
                print(f"{it['id']}: 无 answer 事件（可能直接 error/done）")
                continue
            times.append(first_ms)
            print(f"{it['id']}: firstToken={first_ms:.0f}ms  total={done['latencyMs'] if done else '?'}ms")
        except Exception as e:
            print(f"{it['id']}: ERR {e}")

    if not times:
        print("❌ 无有效测量")
        sys.exit(1)
    times.sort()
    n = len(times)
    p50 = times[n // 2]
    p95 = times[int(n * 0.95) - 1] if n >= 20 else times[-1]
    mean = sum(times) / n
    print(f"\n首 Token: n={n}  mean={mean:.0f}ms  P50={p50:.0f}ms  P95={p95:.0f}ms")
    print(f"P95 ≤ 2000ms: {'✅' if p95 <= 2000 else '❌'}（P1 闸门首 Token ≤ 2s）")


if __name__ == "__main__":
    main()
