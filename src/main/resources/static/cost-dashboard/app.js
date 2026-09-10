const API_BASE = '/api/cost-dashboard';
const alertBox = document.getElementById('alerts');

function showAlert(type, msg) {
  alertBox.innerHTML = `<div class="alert ${type}">${msg}</div>`;
  setTimeout(() => alertBox.innerHTML = '', 5000);
}

async function req(url) {
  const res = await fetch(url, { headers: { 'X-Tenant-Id': 'default' } });
  const j = await res.json();
  if (j.code !== 0) throw new Error(j.msg || '请求失败');
  return j.data;
}

// 加载统计卡片
async function loadStats() {
  try {
    const stats = await req(`${API_BASE}/usage/tenant?period=today`);
    document.getElementById('todayTokens').textContent = formatNumber(stats.totalTokens);
    document.getElementById('todayCost').textContent = `¥${stats.totalCost.toFixed(2)}`;
    document.getElementById('todayCalls').textContent = formatNumber(stats.totalCalls);

    // Token 环比
    const tokenChangeEl = document.getElementById('tokenChange');
    if (stats.tokenGrowthRate > 0) {
      tokenChangeEl.textContent = `↑ ${stats.tokenGrowthRate.toFixed(1)}%`;
      tokenChangeEl.className = 'stat-change up';
    } else if (stats.tokenGrowthRate < 0) {
      tokenChangeEl.textContent = `↓ ${Math.abs(stats.tokenGrowthRate).toFixed(1)}%`;
      tokenChangeEl.className = 'stat-change down';
    } else {
      tokenChangeEl.textContent = '→ 持平';
    }

    // 费用环比
    const costChangeEl = document.getElementById('costChange');
    if (stats.costGrowthRate > 0) {
      costChangeEl.textContent = `↑ ${stats.costGrowthRate.toFixed(1)}%`;
      costChangeEl.className = 'stat-change up';
    } else if (stats.costGrowthRate < 0) {
      costChangeEl.textContent = `↓ ${Math.abs(stats.costGrowthRate).toFixed(1)}%`;
      costChangeEl.className = 'stat-change down';
    } else {
      costChangeEl.textContent = '→ 持平';
    }

    // 预算使用率（需要从配额接口获取）
    try {
      const quotaRes = await fetch('/api/open/quota', { headers: { 'X-Tenant-Id': 'default' } });
      const quotaJson = await quotaRes.json();
      if (quotaJson.code === 0 && quotaJson.data.dailyBudget > 0) {
        const usageRate = (stats.totalCost / quotaJson.data.dailyBudget * 100).toFixed(1);
        document.getElementById('budgetUsage').textContent = `${usageRate}%`;
      } else {
        document.getElementById('budgetUsage').textContent = 'N/A';
      }
    } catch (e) {
      document.getElementById('budgetUsage').textContent = 'N/A';
    }
  } catch (err) {
    showAlert('err', '加载统计失败: ' + err.message);
  }
}

// 加载趋势图表
async function loadTrendCharts() {
  try {
    const trend = await req(`${API_BASE}/trend?days=7`);

    // Token 趋势折线图
    const tokenChart = echarts.init(document.getElementById('tokenTrendChart'));
    tokenChart.setOption({
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: trend.tokenTrend.map(t => t.date) },
      yAxis: { type: 'value', name: 'Token 数' },
      series: [{
        name: 'Token 消耗',
        type: 'line',
        data: trend.tokenTrend.map(t => t.tokens),
        smooth: true,
        areaStyle: { opacity: 0.3 }
      }]
    });

    // 费用趋势折线图
    const costChart = echarts.init(document.getElementById('costTrendChart'));
    costChart.setOption({
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: trend.costTrend.map(t => t.date) },
      yAxis: { type: 'value', name: '费用（元）' },
      series: [{
        name: '费用',
        type: 'line',
        data: trend.costTrend.map(t => t.cost),
        smooth: true,
        areaStyle: { opacity: 0.3 }
      }]
    });
  } catch (err) {
    showAlert('err', '加载趋势图表失败: ' + err.message);
  }
}

// 加载分布图表
async function loadDistributionCharts() {
  try {
    // 应用用量饼图
    const appUsage = await req(`${API_BASE}/usage/app?period=today`);
    const appChart = echarts.init(document.getElementById('appUsageChart'));
    appChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { orient: 'vertical', left: 'left' },
      series: [{
        name: '应用用量',
        type: 'pie',
        radius: '50%',
        data: appUsage.apps.map(a => ({ name: a.appId, value: a.tokens }))
      }]
    });

    // 模型费用饼图
    const modelUsage = await req(`${API_BASE}/usage/model?period=today`);
    const modelChart = echarts.init(document.getElementById('modelUsageChart'));
    modelChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
      legend: { orient: 'vertical', left: 'left' },
      series: [{
        name: '模型费用',
        type: 'pie',
        radius: '50%',
        data: modelUsage.models.map(m => ({ name: m.modelName, value: m.cost }))
      }]
    });
  } catch (err) {
    showAlert('err', '加载分布图表失败: ' + err.message);
  }
}

// 加载 Top 消耗请求
async function loadTopRequests() {
  try {
    const data = await req(`${API_BASE}/top-requests?limit=10`);
    const container = document.getElementById('topRequestsContainer');

    if (!data.requests || data.requests.length === 0) {
      container.innerHTML = '<p style="color:#99a">今日暂无请求记录</p>';
      return;
    }

    let html = '<table class="data-table"><thead><tr>';
    html += '<th>Trace ID</th><th>应用</th><th>模型</th><th>Token 数</th><th>费用</th><th>耗时</th><th>时间</th>';
    html += '</tr></thead><tbody>';

    for (const req of data.requests) {
      html += '<tr>';
      html += `<td><code>${req.traceId.substring(0, 8)}...</code></td>`;
      html += `<td>${req.appId || '-'}</td>`;
      html += `<td>${req.modelName}</td>`;
      html += `<td>${formatNumber(req.tokens)}</td>`;
      html += `<td>¥${req.cost.toFixed(4)}</td>`;
      html += `<td>${req.durationMs}ms</td>`;
      html += `<td>${formatDateTime(req.createdAt)}</td>`;
      html += '</tr>';
    }

    html += '</tbody></table>';
    container.innerHTML = html;
  } catch (err) {
    showAlert('err', '加载 Top 请求失败: ' + err.message);
  }
}

// 加载预算告警历史
async function loadAlertHistory() {
  try {
    const data = await req(`${API_BASE}/budget/alerts?limit=20`);
    const container = document.getElementById('alertHistoryContainer');

    if (!data.alerts || data.alerts.length === 0) {
      container.innerHTML = '<p style="color:#99a">暂无告警记录</p>';
      return;
    }

    let html = '';
    for (const alert of data.alerts) {
      const alertClass = alert.alertType === 'critical' ? 'alert-critical' : 'alert-warning';
      const alertIcon = alert.alertType === 'critical' ? '🚨' : '⚠️';
      const alertLabel = alert.alertType === 'critical' ? '超支' : '预警';

      html += `<div class="alert-item ${alertClass}">`;
      html += `<div class="alert-type">${alertIcon} 预算${alertLabel}</div>`;
      html += `<div class="alert-detail">`;
      html += `使用率: ${alert.usageRate.toFixed(1)}% | `;
      html += `今日已用: ¥${alert.todayUsage.toFixed(2)} | `;
      html += `日预算: ¥${alert.dailyBudget.toFixed(2)} | `;
      html += `时间: ${formatDateTime(alert.notifiedAt)}`;
      html += `</div></div>`;
    }

    container.innerHTML = html;
  } catch (err) {
    showAlert('err', '加载告警历史失败: ' + err.message);
  }
}

// 辅助函数
function formatNumber(num) {
  if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
  if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
  return num.toString();
}

function formatDateTime(isoStr) {
  const d = new Date(isoStr);
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

// 初始化
loadStats();
loadTrendCharts();
loadDistributionCharts();
loadTopRequests();
loadAlertHistory();

// 自动刷新（每 60 秒）
setInterval(() => {
  loadStats();
  loadTopRequests();
  loadAlertHistory();
}, 60000);
