"""
Agent Platform Python SDK 示例
W17 开放平台接入示例代码
"""

import requests
from typing import Optional


class AgentPlatformClient:
    """Agent Platform 开放平台客户端"""

    def __init__(self, api_key: str, base_url: str = "http://localhost:8082"):
        """
        初始化客户端

        Args:
            api_key: API Key（sk- 开头）
            base_url: 平台基础 URL
        """
        self.api_key = api_key
        self.base_url = base_url.rstrip('/')
        self.headers = {
            "X-Api-Key": api_key,
            "Content-Type": "application/json"
        }

    def query(self, question: str, max_rows: int = 100, enable_chart: bool = True) -> dict:
        """
        NL2SQL 数据查询

        Args:
            question: 自然语言问题
            max_rows: 最大返回行数
            enable_chart: 是否生成图表

        Returns:
            查询结果（含 SQL、表格数据、图表配置）
        """
        url = f"{self.base_url}/api/data-agent/query"
        data = {
            "question": question,
            "maxRows": max_rows,
            "enableChart": enable_chart
        }
        response = requests.post(url, json=data, headers=self.headers)
        response.raise_for_status()
        return response.json()

    def query_with_verification(self, question: str, max_rows: int = 100) -> dict:
        """
        NL2SQL + 结论复算校验

        Args:
            question: 自然语言问题
            max_rows: 最大返回行数

        Returns:
            查询结果（含验证报告）
        """
        url = f"{self.base_url}/api/data-agent/query/verify"
        data = {
            "question": question,
            "maxRows": max_rows,
            "requireVerification": True
        }
        response = requests.post(url, json=data, headers=self.headers)
        response.raise_for_status()
        return response.json()

    def search(self, query: str, top_k: int = 5) -> dict:
        """
        RAG 知识检索

        Args:
            query: 检索查询
            top_k: 返回结果数

        Returns:
            检索结果（含文档片段、相关性分数）
        """
        url = f"{self.base_url}/api/rag/search"
        data = {"query": query, "topK": top_k}
        response = requests.post(url, json=data, headers=self.headers)
        response.raise_for_status()
        return response.json()

    def get_usage_stats(self, period: str = "today") -> dict:
        """
        查询用量统计

        Args:
            period: 统计周期（today/yesterday/week/month）

        Returns:
            用量统计（Token 数、费用、环比）
        """
        url = f"{self.base_url}/api/cost-dashboard/usage/tenant"
        params = {"period": period}
        response = requests.get(url, params=params, headers=self.headers)
        response.raise_for_status()
        return response.json()

    def list_api_keys(self) -> dict:
        """
        查询 API Key 列表

        Returns:
            API Key 列表（仅前缀，不含明文）
        """
        url = f"{self.base_url}/api/open/keys"
        response = requests.get(url, headers=self.headers)
        response.raise_for_status()
        return response.json()


# ========== 使用示例 ==========

def main():
    # 初始化客户端（替换为你的 API Key）
    client = AgentPlatformClient(
        api_key="sk-abc123def456ghi789jkl012mno345pqr",
        base_url="http://localhost:8082"
    )

    # 示例 1: NL2SQL 查询
    print("=== 示例 1: NL2SQL 查询 ===")
    result = client.query("上月销售额最高的 3 个产品")
    print(f"问题: {result['data']['question']}")
    print(f"SQL: {result['data']['sql']}")
    print(f"行数: {result['data']['rowCount']}")
    print(f"数据: {result['data']['rows']}")
    print()

    # 示例 2: NL2SQL + 复算校验
    print("=== 示例 2: NL2SQL + 复算校验 ===")
    result = client.query_with_verification("上月销售额和增长率")
    print(f"验证通过: {result['data']['verification']['allPassed']}")
    print(f"验证摘要: {result['data']['verification']['summary']}")
    for detail in result['data']['verification']['details']:
        status = "✅" if detail['passed'] else "❌"
        print(f"  {status} {detail['description']}: 声称 {detail['claimValue']}, 复算 {detail['recalcValue']}")
    print()

    # 示例 3: RAG 知识检索
    print("=== 示例 3: RAG 知识检索 ===")
    result = client.search("差旅报销标准")
    print(f"检索结果数: {len(result['data'])}")
    for i, doc in enumerate(result['data'][:3], 1):
        print(f"  {i}. {doc['content'][:100]}... (分数: {doc['score']:.4f})")
    print()

    # 示例 4: 查询用量统计
    print("=== 示例 4: 查询用量统计 ===")
    result = client.get_usage_stats("today")
    print(f"今日 Token: {result['data']['totalTokens']}")
    print(f"今日费用: ¥{result['data']['totalCost']:.2f}")
    print(f"Token 环比: {result['data']['tokenGrowthRate']:.1f}%")
    print()

    # 示例 5: 查询 API Key 列表
    print("=== 示例 5: 查询 API Key 列表 ===")
    result = client.list_api_keys()
    for key in result['data']:
        print(f"  - {key['apiKeyPrefix']}... ({key['name']}, {key['status']})")
    print()


if __name__ == "__main__":
    main()
