/* Agent 运行管理页（docs/design/api/20260902-admin-pages.md §2.6）
 * GET /api/agent/runs（分页/状态）、GET /runs/{id}、POST /runs、POST /runs/{id}/retry、DELETE /runs/{id} */
(function () {
  const TENANT = 'default';
  const HDR = { 'X-Tenant-Id': TENANT, 'Content-Type': 'application/json' };
  let page = 1;
  const SIZE = 20;

  const $ = (s) => document.querySelector(s);
  const alertsEl = $('#alerts');

  function esc(s) {
    const d = document.createElement('div');
    d.textContent = s == null ? '' : String(s);
    return d.innerHTML;
  }

  function alertBox(type, msg) {
    const el = document.createElement('div');
    el.className = 'alert ' + type;
    el.textContent = msg;
    alertsEl.appendChild(el);
    setTimeout(() => el.remove(), 8000);
  }

  async function api(url, method, body) {
    const resp = await fetch(url, {
      method: method || 'GET',
      headers: HDR,
      body: body == null ? undefined : JSON.stringify(body),
    });
    if (!resp.ok) throw new Error('请求失败 HTTP ' + resp.status);
    const j = await resp.json();
    if (j.code !== 0) throw new Error(j.message || '接口错误');
    return j.data;
  }

  function tagOf(status) {
    const map = { RUNNING: 'run', WAITING_APPROVAL: 'warn', COMPLETED: 'ok',
      FAILED: 'err', TIMEOUT: 'warn', CANCELED: 'idle', CREATED: 'idle' };
    return '<span class="tag ' + (map[status] || 'idle') + '">' + esc(status) + '</span>';
  }

  function fmt(ts) { return ts ? new Date(ts).toLocaleString('zh-CN', { hour12: false }) : '—'; }

  function rowActions(runId, status) {
    let html = '<button data-act="detail" data-id="' + runId + '">详情</button>';
    if (status === 'FAILED' || status === 'TIMEOUT') {
      html += ' <button data-act="retry" data-id="' + runId + '">重试</button>';
    }
    if (status === 'RUNNING' || status === 'WAITING_APPROVAL') {
      html += ' <button class="danger" data-act="cancel" data-id="' + runId + '">取消</button>';
    }
    return html;
  }

  async function loadRuns() {
    const status = $('#statusSel').value;
    const q = new URLSearchParams({ page: String(page), size: String(SIZE) });
    if (status) q.set('status', status);
    try {
      const data = await api('/api/agent/runs?' + q.toString());
      renderRuns(data);
    } catch (e) {
      alertBox('err', '加载运行列表失败：' + e.message);
    }
  }

  function renderRuns(data) {
    const tb = $('#tb');
    const items = data.items || [];
    tb.innerHTML = items.map((it) => '<tr><td>' + it.runId + '</td><td>' + esc(it.appId)
      + '</td><td>' + esc(it.task) + '</td><td>' + tagOf(it.status)
      + '</td><td>' + it.stepsDone + '/' + it.maxSteps + '</td><td>' + it.tokensUsed
      + '</td><td>' + fmt(it.createdAt) + '</td><td>' + rowActions(it.runId, it.status) + '</td></tr>').join('');
    $('#empty').hidden = items.length > 0;
    $('#pager').hidden = data.totalPages <= 1;
    $('#pageInfo').textContent = '第 ' + data.page + ' / ' + data.totalPages + ' 页 · 共 ' + data.total + ' 条';
    $('#prevBtn').disabled = data.page <= 1;
    $('#nextBtn').disabled = data.page >= data.totalPages;
  }

  async function showDetail(runId) {
    try {
      const d = await api('/api/agent/runs/' + runId);
      $('#detailBody').textContent = JSON.stringify(d, null, 2);
      $('#detailCard').hidden = false;
    } catch (e) {
      alertBox('err', '加载详情失败：' + e.message);
    }
  }

  async function retryRun(runId) {
    if (!confirm('确认重试运行 ' + runId + '？')) return;
    try {
      const newRun = await api('/api/agent/runs/' + runId + '/retry', 'POST');
      alertBox('ok', '已创建新运行：runId=' + newRun);
      loadRuns();
    } catch (e) {
      alertBox('err', '重试失败：' + e.message);
    }
  }

  async function cancelRun(runId) {
    if (!confirm('确认取消运行 ' + runId + '？')) return;
    try {
      await api('/api/agent/runs/' + runId, 'DELETE');
      alertBox('ok', '已提交取消');
      loadRuns();
    } catch (e) {
      alertBox('err', '取消失败：' + e.message);
    }
  }

  $('#submitForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const task = $('#taskInput').value.trim();
    const btn = e.target.querySelector('button');
    btn.disabled = true;
    try {
      const runId = await api('/api/agent/runs', 'POST', { task: task });
      alertBox('ok', '任务已提交，runId=' + runId + '（异步执行）');
      $('#taskInput').value = '';
      page = 1;
      loadRuns();
    } catch (err) {
      alertBox('err', '提交失败：' + err.message);
    } finally {
      btn.disabled = false;
    }
  });

  $('#filterForm').addEventListener('submit', (e) => {
    e.preventDefault();
    page = 1;
    loadRuns();
  });
  $('#prevBtn').addEventListener('click', () => { if (page > 1) { page--; loadRuns(); } });
  $('#nextBtn').addEventListener('click', () => { page++; loadRuns(); });
  $('#detailClose').addEventListener('click', () => { $('#detailCard').hidden = true; });

  document.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const act = btn.dataset.act;
    const id = Number(btn.dataset.id);
    if (act === 'detail') showDetail(id);
    else if (act === 'retry') retryRun(id);
    else if (act === 'cancel') cancelRun(id);
  });

  loadRuns();
})();