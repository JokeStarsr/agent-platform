const API_BASE = '/api/data-agent';
const LINEAGE_BASE = '/api/data-lineage';
const alertBox = document.getElementById('alerts');
let currentTraceId = null;

function showAlert(type, msg) {
  alertBox.innerHTML = `<div class="alert ${type}">${msg}</div>`;
  setTimeout(() => alertBox.innerHTML = '', 5000);
}

async function req(url, method = 'GET', body) {
  const opt = { method, headers: { 'X-Tenant-Id': 'default', 'Content-Type': 'application/json' } };
  if (body) opt.body = JSON.stringify(body);
  const res = await fetch(url, opt);
  const j = await res.json();
  if (j.code !== 0) throw new Error(j.msg || '请求失败');
  return j.data;
}

// 查询表单提交
document.getElementById('queryForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const question = document.getElementById('question').value.trim();
  if (!question) return;

  const enableVerification = document.getElementById('enableVerification').checked;
  const enableChart = document.getElementById('enableChart').checked;

  const submitBtn = e.target.querySelector('button[type="submit"]');
  submitBtn.disabled = true;
  submitBtn.textContent = '查询中...';

  try {
    // 调用 NL2SQL API
    const endpoint = enableVerification ? `${API_BASE}/query/verify` : `${API_BASE}/query`;
    const result = await req(endpoint, 'POST', { question, maxRows: 100, requireVerification: enableVerification });

    // 显示结果
    displayResults(question, result, enableChart);
    showAlert('ok', '查询成功');
  } catch (err) {
    showAlert('err', err.message);
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = '查询';
  }
});

function displayResults(question, result, enableChart) {
  document.getElementById('resultSection').hidden = false;

  // 显示 SQL
  document.getElementById('sqlDisplay').textContent = result.sql;

  // 显示元数据
  const metaHtml = `
    <strong>问题</strong>: ${escapeHtml(question)}<br>
    <strong>行数</strong>: ${result.rowCount}${result.truncated ? ' (已截断)' : ''}<br>
    <strong>耗时</strong>: ${result.totalDurationMs} ms (LLM: ${result.llmDurationMs} ms, SQL: ${result.sqlDurationMs} ms)
  `;
  document.getElementById('resultMeta').innerHTML = metaHtml;

  // 显示数据表格
  renderDataTable(result.columns, result.rows);

  // 生成图表
  if (enableChart && result.rows.length > 0) {
    generateChart(question, result);
  }

  // 显示验证结果
  if (result.verification) {
    displayVerification(result.verification);
  }

  // 记录血缘
  recordLineage(question, result);
}

function renderDataTable(columns, rows) {
  if (!columns || columns.length === 0 || !rows || rows.length === 0) {
    document.getElementById('dataTableContainer').innerHTML = '<p style="color:#99a">无数据</p>';
    return;
  }

  let html = '<table class="data-table"><thead><tr>';
  for (const col of columns) {
    html += `<th>${escapeHtml(col)}</th>`;
  }
  html += '</tr></thead><tbody>';

  for (const row of rows) {
    html += '<tr>';
    for (const cell of row) {
      html += `<td>${cell !== null && cell !== undefined ? escapeHtml(String(cell)) : ''}</td>`;
    }
    html += '</tr>';
  }
  html += '</tbody></table>';

  document.getElementById('dataTableContainer').innerHTML = html;
}

async function generateChart(question, result) {
  try {
    // 调用图表生成 API（这里简化：前端直接生成）
    const chartType = detectChartType(result.columns, result.rows);
    if (chartType === 'none') {
      document.getElementById('chartCard').hidden = true;
      return;
    }

    document.getElementById('chartCard').hidden = false;
    document.getElementById('chartInfo').innerHTML = `<strong>图表类型</strong>: ${chartType.description}`;

    // 渲染 ECharts
    const chartDom = document.getElementById('chartContainer');
    const chart = echarts.init(chartDom);
    const option = buildEChartsOption(question, result, chartType);
    chart.setOption(option);
  } catch (err) {
    console.error('图表生成失败:', err);
    document.getElementById('chartCard').hidden = true;
  }
}

function detectChartType(columns, rows) {
  if (!columns || columns.length === 0 || !rows || rows.length === 0) {
    return { type: 'none', description: '' };
  }

  // 简单规则：
  // - 单行单列 → KPI
  // - 有时间列 + 数值列 → 折线图
  // - 有分类列 + 数值列 → 柱状图/饼图
  // - 其他 → 表格

  if (rows.length === 1 && columns.length <= 2) {
    return { type: 'kpi', description: 'KPI 卡片（单值聚合）' };
  }

  const timeCol = columns.findIndex(c => /date|time|month|year|day/i.test(c));
  const numCol = columns.findIndex(c => /count|sum|avg|total|amount/i.test(c));
  const catCol = columns.findIndex(c => /name|category|type|status/i.test(c));

  if (timeCol >= 0 && numCol >= 0) {
    return { type: 'line', description: '折线图（时间序列）', timeCol, numCol };
  }

  if (catCol >= 0 && numCol >= 0) {
    return { type: 'bar', description: '柱状图（分类对比）', catCol, numCol };
  }

  return { type: 'none', description: '' };
}

function buildEChartsOption(question, result, chartType) {
  const { columns, rows } = result;

  if (chartType.type === 'kpi') {
    const value = rows[0][0];
    return {
      title: { text: question, left: 'center' },
      graphic: [{
        type: 'text',
        left: 'center',
        top: '40%',
        style: { text: columns[0], fontSize: 16, fill: '#666' }
      }, {
        type: 'text',
        left: 'center',
        top: '55%',
        style: { text: String(value), fontSize: 36, fontWeight: 'bold', fill: '#333' }
      }]
    };
  }

  if (chartType.type === 'line') {
    const xData = rows.map(r => String(r[chartType.timeCol]));
    const yData = rows.map(r => Number(r[chartType.numCol]) || 0);
    return {
      title: { text: question, left: 'center' },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: xData },
      yAxis: { type: 'value' },
      series: [{ name: columns[chartType.numCol], type: 'line', data: yData, smooth: true }]
    };
  }

  if (chartType.type === 'bar') {
    const xData = rows.map(r => String(r[chartType.catCol]));
    const yData = rows.map(r => Number(r[chartType.numCol]) || 0);
    return {
      title: { text: question, left: 'center' },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: xData },
      yAxis: { type: 'value' },
      series: [{ name: columns[chartType.numCol], type: 'bar', data: yData }]
    };
  }

  return {};
}

function displayVerification(verification) {
  const card = document.getElementById('verificationCard');
  card.hidden = false;

  const statusClass = verification.allPassed ? 'verification-pass' : 'verification-fail';
  const statusText = verification.allPassed ? '✅ 全部通过' : '❌ 存在不一致';

  let html = `<p><strong>状态</strong>: <span class="${statusClass}">${statusText}</span></p>`;
  html += `<p><strong>摘要</strong>: ${escapeHtml(verification.summary)}</p>`;

  if (verification.details && verification.details.length > 0) {
    html += '<table class="data-table"><thead><tr>';
    html += '<th>结论</th><th>声称值</th><th>复算值</th><th>状态</th>';
    html += '</tr></thead><tbody>';
    for (const detail of verification.details) {
      html += '<tr>';
      html += `<td>${escapeHtml(detail.description)}</td>`;
      html += `<td>${escapeHtml(detail.claimValue)}</td>`;
      html += `<td>${escapeHtml(detail.recalcValue)}</td>`;
      html += `<td>${detail.passed ? '✅' : '❌'}</td>`;
      html += '</tr>';
    }
    html += '</tbody></table>';
  }

  document.getElementById('verificationResult').innerHTML = html;
}

async function recordLineage(question, result) {
  try {
    // 血缘记录由后端自动创建，这里获取 traceId
    currentTraceId = result.traceId || generateTraceId();

    const card = document.getElementById('lineageCard');
    card.hidden = false;

    const html = `
      <strong>追踪 ID</strong>: <code>${currentTraceId}</code><br>
      <strong>涉及表</strong>: ${result.tablesUsed ? result.tablesUsed.join(', ') : 'N/A'}<br>
      <strong>涉及列</strong>: ${result.columnsUsed ? result.columnsUsed.length : 'N/A'} 列
    `;
    document.getElementById('lineageInfo').innerHTML = html;
  } catch (err) {
    console.error('血缘记录失败:', err);
  }
}

function generateTraceId() {
  return 'trace-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9);
}

// 导出按钮
document.getElementById('exportMarkdown').addEventListener('click', () => {
  if (currentTraceId) {
    window.open(`${LINEAGE_BASE}/export/markdown/${currentTraceId}`, '_blank');
  }
});

document.getElementById('exportHtml').addEventListener('click', () => {
  if (currentTraceId) {
    window.open(`${LINEAGE_BASE}/export/html/${currentTraceId}`, '_blank');
  }
});

function escapeHtml(text) {
  if (text === null || text === undefined) return '';
  const div = document.createElement('div');
  div.textContent = String(text);
  return div.innerHTML;
}
