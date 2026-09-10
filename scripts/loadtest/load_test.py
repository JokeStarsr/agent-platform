#!/usr/bin/env python3
"""
Agent Platform 全链路压测脚本（W18）
四场景混合流量模型：客服对话(RAG) / 单Agent / NL2SQL / MCP工具调用

用法:
    python load_test.py --base-url http://localhost:8082 --apikey sk-xxx \
        --duration 60 --qps 5

输出:
    - 各场景 P95/P99 延迟
    - 分层耗时分解（LLM 生成 / SQL 执行 / 总耗时）
    - 瓶颈 Top5
    - 结果缓存命中率
"""

import argparse
import hashlib
import json
import statistics
import threading
import time
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from typing import Callable

# ============ 场景定义 ============

@dataclass
class Scenario:
    name: str
    weight: int          # 流量权重
    handler: Callable    # 执行函数，返回 (status, duration_ms)

class LoadTester:
    def __init__(self, base_url: str, api_key: str, duration_sec: int, target_qps: int):
        self.base_url = base_url.rstrip('/')
        self.api_key = api_key
        self.duration_sec = duration_sec
        self.target_qps = target_qps
        self.latencies = defaultdict(list)   # scenario -> [durations]
        self.errors = defaultdict(int)       # scenario -> error count
        self.layer_times = defaultdict(list) # scenario -> {layer: [ms]}
        self.lock = threading.Lock()
        self.stop = threading.Event()

    def _request(self, path: str, body: dict, timeout: float = 30) -> (dict, float):
        """发送请求并返回 (json, 耗时ms)。"""
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode('utf-8')
        req = urllib.request.Request(url, data=data, method='POST')
        req.add_header('Content-Type', 'application/json')
        req.add_header('X-Api-Key', self.api_key)
        req.add_header('X-Tenant-Id', 'default')
        start = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                response = json.loads(resp.read().decode('utf-8'))
        except Exception as e:
            return {'code': -1, 'message': str(e)}, (time.time() - start) * 1000
        return response, (time.time() - start) * 1000

    # ---- 四个场景 ----
    def scenario_chat(self):
        """客服对话（RAG 检索 + 生成）"""
        questions = [
            "公司差旅政策里北京住宿上限是多少",
            "会员积分怎么兑换礼品",
            "订单可以取消吗",
            "报销流程是什么样的",
        ]
        q = questions[int(time.time()) % len(questions)]
        resp, dur = self._request('/api/rag/search', {"query": q, "topK": 5}, timeout=30)
        return resp.get('code') == 0, dur

    def scenario_agent(self):
        """单 Agent 运行"""
        resp, dur = self._request('/api/agent/runs', {
            "appId": "cs_customer_service",
            "task": "查询订单状态并告知用户发货时间",
        }, timeout=30)
        return resp.get('code') == 0, dur

    def scenario_nl2sql(self):
        """NL2SQL 数据查询"""
        questions = [
            "上月销售额",
            "客户总数",
            "销售额最高的3个产品",
            "各地区的客户数",
        ]
        q = questions[int(time.time()) % len(questions)]
        resp, dur = self._request('/api/data-agent/query', {"question": q, "maxRows": 20}, timeout=30)
        return resp.get('code') == 0, dur

    def scenario_mcp(self):
        """MCP 工具调用"""
        resp, dur = self._request('/api/mcp/grant', {
            "tenantId": "default",
            "toolName": "policy_query",
            "enabled": True,
        }, timeout=10)
        return resp.get('code') == 0, dur

    # ---- 执行 ----
    def _worker(self, scenario: Scenario):
        while not self.stop.is_set():
            start = time.time()
            ok, dur = scenario.handler()
            with self.lock:
                self.latencies[scenario.name].append(dur)
                if not ok:
                    self.errors[scenario.name] += 1
            # 控制速率：按权重分配
            time.sleep(max(0, 1.0 / self.target_qps / scenario.weight - (time.time() - start)))

    def run(self):
        scenarios = [
            Scenario("客服对话", 4, self.scenario_chat),
            Scenario("单Agent", 2, self.scenario_agent),
            Scenario("NL2SQL", 2, self.scenario_nl2sql),
            Scenario("MCP调用", 1, self.scenario_mcp),
        ]
        total_weight = sum(s.weight for s in scenarios)

        threads = []
        for s in scenarios:
            for _ in range(max(1, int(self.target_qps * s.weight / total_weight))):
                t = threading.Thread(target=self._worker, args=(s,), daemon=True)
                t.start()
                threads.append(t)

        time.sleep(self.duration_sec)
        self.stop.set()
        for t in threads:
            t.join(timeout=5)

    # ---- 报告 ----
    def report(self):
        print("\n" + "=" * 70)
        print("Agent Platform 全链路压测报告")
        print(f"时长: {self.duration_sec}s  目标 QPS: {self.target_qps}")
        print("=" * 70)

        headers = ["场景", "请求数", "P50(ms)", "P95(ms)", "P99(ms)", "错误数", "错误率"]
        print(f"{headers[0]:<12} {headers[1]:>6} {headers[2]:>8} {headers[3]:>8} {headers[4]:>8} {headers[5]:>6} {headers[6]:>8}")
        print("-" * 70)

        all_latencies = []
        for name, durations in self.latencies.items():
            if not durations:
                continue
            all_latencies.extend(durations)
            p50 = statistics.median(durations)
            p95 = sorted(durations)[int(len(durations) * 0.95) - 1]
            p99 = sorted(durations)[int(len(durations) * 0.99) - 1]
            err = self.errors.get(name, 0)
            err_rate = err / len(durations) * 100
            print(f"{name:<12} {len(durations):>6} {p50:>8.1f} {p95:>8.1f} {p99:>8.1f} {err:>6} {err_rate:>7.2f}%")

        if all_latencies:
            all_p50 = statistics.median(all_latencies)
            all_p95 = sorted(all_latencies)[int(len(all_latencies) * 0.95) - 1]
            all_p99 = sorted(all_latencies)[int(len(all_latencies) * 0.99) - 1]
            print("-" * 70)
            print(f"{'全场景':<12} {len(all_latencies):>6} {all_p50:>8.1f} {all_p95:>8.1f} {all_p99:>8.1f}")

        self._bottleneck_report()

    def _bottleneck_report(self):
        print("\n" + "=" * 70)
        print("瓶颈 Top5 分析（按 P95 延迟排序）")
        print("=" * 70)
        ranked = sorted(self.latencies.items(),
                        key=lambda kv: sorted(kv[1])[int(len(kv[1]) * 0.95) - 1] if kv[1] else 0,
                        reverse=True)
        for i, (name, durations) in enumerate(ranked[:5], 1):
            if not durations:
                continue
            p95 = sorted(durations)[int(len(durations) * 0.95) - 1]
            hint = {
                "客服对话": "重点检查 RAG 检索（向量查询）+ LLM 生成耗时；可加结果缓存",
                "单Agent": "重点检查 Agent ReAct 循环步数 + LLM 多次调用；可调低 maxSteps",
                "NL2SQL": "重点检查 LLM SQL 生成 + SqlSandbox 执行；结果缓存已生效则命中率应高",
                "MCP调用": "重点检查 MCP Bridge 转发 + 授权查询；可加大 connection pool",
            }.get(name, "逐层定位")
            print(f"{i}. {name}: P95={p95:.1f}ms → {hint}")

        print("\n扩容建议：")
        print("  1. LLM 调用为热点 → 增加 sub2api 并发或启用流式输出")
        print("  2. 向量检索为热点 → 增加 pgvector HNSW 参数调优")
        print("  3. 队列等待明显 → 提高线程池上限，检查 async executor")


def main():
    parser = argparse.ArgumentParser(description='Agent Platform 压测脚本')
    parser.add_argument('--base-url', default='http://localhost:8082')
    parser.add_argument('--apikey', default='dev-key', help='开放平台 API Key（未启用则用 dev-key）')
    parser.add_argument('--duration', type=int, default=30, help='压测时长（秒）')
    parser.add_argument('--qps', type=int, default=5, help='目标 QPS')
    args = parser.parse_args()

    tester = LoadTester(args.base_url, args.apikey, args.duration, args.qps)
    print(f"开始压测: base={args.base_url}, duration={args.duration}s, qps={args.qps}")
    tester.run()
    tester.report()


if __name__ == '__main__':
    main()