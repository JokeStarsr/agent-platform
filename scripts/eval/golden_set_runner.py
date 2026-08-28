#!/usr/bin/env python3
"""
Golden Set Evaluation Runner
================================
P1 收口评测 runner：读取 golden-set.json → 调用 RAG 在线检索 → 计算指标 → 输出 markdown 报告

使用前准备：
1. 确保 agent-platform 应用已启动 (mvn spring-boot:run)
2. DEEPSEEK_API_KEY 环境变量已配置
3. golden-set.json 已放在 src/test/resources/golden-set/v1/

注意：实际调用 LlmGateway/VectorStore 需要 Spring Boot 容器，
此脚本提供核心逻辑结构，实际跑分请在 IDEA 中通过 JUnit 或直接运行应用后调用接口。

Usage:
    python3 golden_set_runner.py --json ./src/test/resources/golden-set/v1/golden-set.json
"""

import json
import sys
import os
from typing import List, Dict, Any, Optional
from pathlib import Path

# ===== 配置区域 =====
# 修改为你的应用实际运行地址
BASE_URL = os.getenv("GOLDEN_SET_BASE_URL", "http://localhost:8080")  # agent-platform 运行端口
API_PREFIX = "/api/rag"  # RAG 在线检索接口前缀

# ===== 评价指标阈值 (对应ADS P1闸门) =====
THRESHOLDS = {
    "faithfulness": 0.90,      # 忠实度下限
    "recall_at_5": 0.85,       # 召回率下限
    "citation_rate": 0.95,     # 引用完整率下限
}

# ===== 工具函数 =====

def ngram_set(text: str, n: int = 2) -> set:
    """中文友好的字符 n-gram 集合（中文无空格，用相邻字符组合比较）"""
    import re
    # 去除标点、空白、引用标记，保留中文/数字/字母
    clean = re.sub(r"[【】\[\]#\d\.\s、，。；：！？（）()\"'\-]", "", text)
    if len(clean) < n:
        return {clean} if clean else set()
    return {clean[i:i + n] for i in range(len(clean) - n + 1)}


def deep_compare(a: str, b: str) -> float:
    """忠实度估计：答案与标准答案的字符 bigram Jaccard 相似度（中文友好）
    实际生产用 LLM-as-judge，此为轻量离线近似
    """
    if not a or not b:
        return 0.0
    set_a = ngram_set(a)
    set_b = ngram_set(b)
    if not set_a or not set_b:
        return 0.0
    intersection = len(set_a & set_b)
    union = len(set_a | set_b)
    return intersection / union if union > 0 else 0.0


def call_rag_service(query: str, topK: int = 5, tenantId: str = "default") -> Dict[str, Any]:
    """
    调用后端 RAG 在线检索全管道接口 /api/rag/search
    返回：{answer, citations, sourceChunks, latencyMs, status, ...}
    """
    import urllib.request
    import urllib.error

    url = f"{BASE_URL}{API_PREFIX}/search"
    payload = json.dumps({"query": query, "topK": topK}).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "X-Tenant-Id": tenantId,
        }
    )

    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            result = json.loads(resp.read().decode("utf-8"))
            data = result.get("data", {})
            return {
                "answer": data.get("answer", ""),
                "citations": data.get("citations", []),
                "sourceChunks": data.get("sourceChunks", []),
                "latencyMs": data.get("latencyMs", 0),
                "status": "ok" if result.get("code", -1) == 0 else f"error_{result.get('code')}",
            }
    except urllib.error.HTTPError as e:
        return {
            "answer": f"[HTTP {e.code}] {e.read().decode('utf-8', errors='ignore')}",
            "citations": [], "sourceChunks": [], "latencyMs": 0,
            "status": f"error_{e.code}",
        }
    except Exception as e:
        return {
            "answer": f"[Exception] {str(e)}",
            "citations": [], "sourceChunks": [], "latencyMs": 0,
            "status": "exception",
        }


# ===== 核心评测逻辑 =====

def _has_citation(answer: str) -> bool:
    """检查答案是否含引用编号（【1】 格式）"""
    import re
    return bool(re.search(r"【\d+】", answer))


def evaluate_item(item: Dict[str, Any]) -> Dict[str, Any]:
    """评测单个 golden-set 条目"""
    question = item["question"]
    expected = item["expect"]

    # 1) 调用 RAG 在线检索全管道
    rag_result = call_rag_service(question, topK=5, tenantId="default")
    answer = rag_result.get("answer", "")
    citations = rag_result.get("citations", [])
    source_chunks = rag_result.get("sourceChunks", [])

    # 2) 忠实度：答案与标准答案的字符 bigram 相似度（中文友好，离线近似）
    faithfulness_score = deep_compare(answer, expected.get("answer", ""))

    # 3) 召回率 Recall@5：标准答案来源(answerSource 文档章节) 是否被检索命中
    #    简化：标准答案核心子串是否出现在 Top-K 检索切片中
    expected_answer = expected.get("answer", "")
    expected_key = expected_answer[2:12] if len(expected_answer) > 12 else expected_answer  # 取标准答案开头核心片段
    recall_score = 0.0
    if source_chunks:
        for chunk in source_chunks:
            if expected_key and (expected_key in chunk or chunk[:10] in expected_answer):
                recall_score = 1.0
                break
        # 若没匹配上核心片段，用标准答案与切片的最大 bigram 相似度兜底
        if recall_score == 0.0:
            chunk_sims = [deep_compare(expected_answer, chunk[:200]) for chunk in source_chunks]
            recall_score = max(chunk_sims) if chunk_sims else 0.0

    # 4) 引用完整率：答案是否带引用编号，且 citations 非空
    citation_rate = 1.0 if (_has_citation(answer) and citations) else 0.0

    # 5) 转人工判定：shouldAnswer=false 的条目必须不硬答
    should_answer = expected.get("shouldAnswer", True)
    if should_answer:
        handoff_flag = True  # 期望回答的条目：回答了即通过
    else:
        # 期望拒答/转人工：若 LLM 仍在硬答（回答且置信度低）则判失败
        answered = len(answer) > 0 and "我不知道" not in answer and "转人工" not in answer
        handoff_flag = not answered

    # 6) 整体判定：是否达标
    all_pass = (
        faithfulness_score >= THRESHOLDS["faithfulness"] and
        recall_score >= THRESHOLDS["recall_at_5"] and
        citation_rate >= THRESHOLDS["citation_rate"] and
        handoff_flag
    )

    return {
        "id": item["id"],
        "question": question,
        "expected_answer": expected_answer,
        "generated_answer": answer,
        "faithfulness": round(faithfulness_score, 3),
        "recall_at_5": round(recall_score, 3),
        "citation_rate": round(citation_rate, 3),
        "handoff": "通过" if handoff_flag else "需转人工",
        "overall": "达标" if all_pass else "未达标",
        "details": {
            "faithfulness_threshold": THRESHOLDS["faithfulness"],
            "recall_threshold": THRESHOLDS["recall_at_5"],
            "citation_threshold": THRESHOLDS["citation_rate"],
        }
    }


# ===== 报告生成 =====

def generate_report(results: List[Dict[str, Any]]) -> str:
    """生成 markdown 格式评测报告"""
    total = len(results)
    passed = sum(1 for r in results if r["overall"] == "达标")
    failed = total - passed

    # 计算平均分
    avg_faithfulness = sum(r["faithfulness"] for r in results) / total if total else 0
    avg_recall = sum(r["recall_at_5"] for r in results) / total if total else 0
    avg_citation = sum(r["citation_rate"] for r in results) / total if total else 0

    lines = []
    lines.append("# Golden Set 评测报告")
    lines.append(f"**生成时间**: {__import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"**总条目**: {total} | **达标**: {passed} | **未达标**: {failed}")
    lines.append("")

    # 挮表
    lines.append("## 指标概览")
    lines.append(f"- **忠实度**: 平均 {avg_faithfulness:.3f} (阈值: {THRESHOLDS['faithfulness']})")
    lines.append(f"- **召回 Recall@5**: 平均 {avg_recall:.3f} (阈值: {THRESHOLDS['recall_at_5']})")
    lines.append(f"- **引用完整率**: 平均 {avg_citation:.3f} (阈值: {THRESHOLDS['citation_rate']})")
    lines.append("")

    # 逐条明细
    lines.append("## 逐条明细")
    lines.append("| ID | 问题 | 结果 | 忠实度 | 召回 | 引用 | 人工转换 |")
    lines.append("|----|------|------|--------|------|-------|----------|")

    for r in results:
        lines.append(
            f"| {r['id']} | {r['question'][:30]}... | {r['overall']} | "
            f"{r['faithfulness']} | {r['recall_at_5']} | {r['citation_rate']} | "
            f"{r['handoff']} |"
        )

    lines.append("")
    lines.append("## 未达标项分析")
    failed_items = [r for r in results if r["overall"] == "未达标"]
    for item in failed_items:
        lines.append(f"- **{item['id']}**: {item['question']}")
        lines.append(f"  - 忠实度不足: {item['faithfulness']} < {THRESHOLDS['faithfulness']}")
        lines.append(f"  - 召回率不足: {item['recall_at_5']} < {THRESHOLDS['recall_at_5']}")
        lines.append(f"  - 引用不完整: {item['citation_rate']} < {THRESHOLDS['citation_rate']}")

    lines.append("")
    lines.append("## 结论")
    if all_pass := all(r["overall"] == "达标" for r in results):
        lines.append("✅ 所有条目达标，P1 收口检查点通过")
    else:
        lines.append(f"⚠️ {len(failed_items)}/{total} 条未达标，建议优化 Prompt / 检索参数 / 知识库质量")

    return "\n".join(lines)


# ===== 主入口 =====

def main():
    if len(sys.argv) < 3:
        print("使用方法: python3 golden_set_runner.py --json <golden-set.json路径>")
        sys.exit(1)

    # 解析参数
    try:
        flag_idx = sys.argv.index("--json")
        json_path = sys.argv[flag_idx + 1]
    except (ValueError, IndexError):
        print("使用方法: python3 golden_set_runner.py --json <golden-set.json路径>")
        sys.exit(1)

    if not os.path.isabs(json_path) and not os.path.exists(json_path):
        json_path = os.path.join(os.getcwd(), json_path)

    if not os.path.exists(json_path):
        print(f"❌ 文件不存在: {json_path}")
        sys.exit(1)

    # 读取 golden-set
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    items = data.get("items", [])
    if not items:
        print("❌ golden-set.json 中没有 items 条目")
        sys.exit(1)

    print(f"📊 开始评测 {len(items)} 条 Golden Set 条目...")
    print(f"   接口地址: {BASE_URL}{API_PREFIX}")
    print()

    # 逐条评测
    results = []
    for i, item in enumerate(items, 1):
        print(f"[{i}/{len(items)}] 评测: {item['id']} - {item['question'][:40]}...")
        result = evaluate_item(item)
        results.append(result)
        print(f"   → 结果: {result['overall']} (忠实度={result['faithfulness']:.2f}, 召回={result['recall_at_5']:.2f})")

    # 生成报告
    report = generate_report(results)

    # 输出到控制台和文件
    report_path = json_path.replace(".json", "_report.md")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report)

    print()
    print("=" * 50)
    print(report)
    print("=" * 50)
    print(f"\n📄 报告已保存至: {report_path}")


if __name__ == "__main__":
    main()