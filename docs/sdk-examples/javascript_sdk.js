/**
 * Agent Platform JavaScript SDK 示例
 * W17 开放平台接入示例代码
 */

class AgentPlatformClient {
  /**
   * 初始化客户端
   * @param {string} apiKey - API Key（sk- 开头）
   * @param {string} baseUrl - 平台基础 URL
   */
  constructor(apiKey, baseUrl = 'http://localhost:8082') {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.replace(/\/$/, '');
  }

  /**
   * 通用请求方法
   */
  async request(method, path, data = null, params = null) {
    let url = `${this.baseUrl}${path}`;

    // 添加查询参数
    if (params) {
      const queryString = new URLSearchParams(params).toString();
      url += `?${queryString}`;
    }

    const options = {
      method,
      headers: {
        'X-Api-Key': this.apiKey,
        'Content-Type': 'application/json'
      }
    };

    if (data && method !== 'GET') {
      options.body = JSON.stringify(data);
    }

    const response = await fetch(url, options);

    if (!response.ok) {
      const error = await response.json();
      throw new Error(`HTTP ${response.status}: ${error.message || response.statusText}`);
    }

    return await response.json();
  }

  /**
   * NL2SQL 数据查询
   * @param {string} question - 自然语言问题
   * @param {number} maxRows - 最大返回行数
   * @param {boolean} enableChart - 是否生成图表
   * @returns {Promise<Object>} 查询结果
   */
  async query(question, maxRows = 100, enableChart = true) {
    return await this.request('POST', '/api/data-agent/query', {
      question,
      maxRows,
      enableChart
    });
  }

  /**
   * NL2SQL + 结论复算校验
   * @param {string} question - 自然语言问题
   * @param {number} maxRows - 最大返回行数
   * @returns {Promise<Object>} 查询结果（含验证报告）
   */
  async queryWithVerification(question, maxRows = 100) {
    return await this.request('POST', '/api/data-agent/query/verify', {
      question,
      maxRows,
      requireVerification: true
    });
  }

  /**
   * RAG 知识检索
   * @param {string} query - 检索查询
   * @param {number} topK - 返回结果数
   * @returns {Promise<Object>} 检索结果
   */
  async search(query, topK = 5) {
    return await this.request('POST', '/api/rag/search', {
      query,
      topK
    });
  }

  /**
   * 查询用量统计
   * @param {string} period - 统计周期（today/yesterday/week/month）
   * @returns {Promise<Object>} 用量统计
   */
  async getUsageStats(period = 'today') {
    return await this.request('GET', '/api/cost-dashboard/usage/tenant', null, { period });
  }

  /**
   * 查询 API Key 列表
   * @returns {Promise<Object>} API Key 列表
   */
  async listApiKeys() {
    return await this.request('GET', '/api/open/keys');
  }
}

// ========== 使用示例 ==========

async function main() {
  // 初始化客户端（替换为你的 API Key）
  const client = new AgentPlatformClient(
    'sk-abc123def456ghi789jkl012mno345pqr',
    'http://localhost:8082'
  );

  try {
    // 示例 1: NL2SQL 查询
    console.log('=== 示例 1: NL2SQL 查询 ===');
    const result1 = await client.query('上月销售额最高的 3 个产品');
    console.log(`问题: ${result1.data.question}`);
    console.log(`SQL: ${result1.data.sql}`);
    console.log(`行数: ${result1.data.rowCount}`);
    console.log(`数据:`, result1.data.rows);
    console.log();

    // 示例 2: NL2SQL + 复算校验
    console.log('=== 示例 2: NL2SQL + 复算校验 ===');
    const result2 = await client.queryWithVerification('上月销售额和增长率');
    console.log(`验证通过: ${result2.data.verification.allPassed}`);
    console.log(`验证摘要: ${result2.data.verification.summary}`);
    for (const detail of result2.data.verification.details) {
      const status = detail.passed ? '✅' : '❌';
      console.log(`  ${status} ${detail.description}: 声称 ${detail.claimValue}, 复算 ${detail.recalcValue}`);
    }
    console.log();

    // 示例 3: RAG 知识检索
    console.log('=== 示例 3: RAG 知识检索 ===');
    const result3 = await client.search('差旅报销标准');
    console.log(`检索结果数: ${result3.data.length}`);
    for (let i = 0; i < Math.min(3, result3.data.length); i++) {
      const doc = result3.data[i];
      console.log(`  ${i + 1}. ${doc.content.substring(0, 100)}... (分数: ${doc.score.toFixed(4)})`);
    }
    console.log();

    // 示例 4: 查询用量统计
    console.log('=== 示例 4: 查询用量统计 ===');
    const result4 = await client.getUsageStats('today');
    console.log(`今日 Token: ${result4.data.totalTokens}`);
    console.log(`今日费用: ¥${result4.data.totalCost.toFixed(2)}`);
    console.log(`Token 环比: ${result4.data.tokenGrowthRate.toFixed(1)}%`);
    console.log();

    // 示例 5: 查询 API Key 列表
    console.log('=== 示例 5: 查询 API Key 列表 ===');
    const result5 = await client.listApiKeys();
    for (const key of result5.data) {
      console.log(`  - ${key.apiKeyPrefix}... (${key.name}, ${key.status})`);
    }
    console.log();

  } catch (error) {
    console.error('错误:', error.message);
  }
}

// 运行示例
main();

// ========== Node.js 环境使用 ==========
// 如果在 Node.js 环境使用，需要安装 node-fetch：
// npm install node-fetch
//
// 然后在文件顶部添加：
// const fetch = require('node-fetch');
//
// 其余代码保持不变
