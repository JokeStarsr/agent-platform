/* Workflow 引擎管理页（docs/design/api/20260902-admin-pages.md §2.6）
 * GET /api/workflow/flows、/flows/{name}、/instances（分页/状态筛选） */
(function () {
  const TENANT = 'default';
  const HDR = { 'X-Tenant-Id': TENANT };
  let page = 1;
  const SIZE = 20;

  const $ = (s) => document.querySelector(s);
  const alertsEl = $('#alerts');
  const flowsEl = $('#flows');
  const flowDefEl = $('#flowDef');
  const tb = $('#instTb');
  const emptyEl = $('#empty');
  const pagerEl = $('#pager');

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

  async function api(url, method) {
    const resp = await fetch(url, { method: method || 'GET', headers: HDR });
    if (!resp.ok) throw new Error('请求失败 HTTP ' + resp.status);
    const body = await resp.json();
    if (body.code !== 0) throw new Error(body.message || '接口错误');
    return body.data;
  }

  function tagOf(status) {
    const map = { RUNNING: 'run', WAITING_APPROVAL: 'warn', COMPLETED: 'ok',
      FAILED: 'err', CANCELED: 'idle', CREATED: 'idle' };
    return '<span class="tag ' + (map[status] || 'idle') + '">' + esc(status) + '</span>';
  }

  function fmt(ts) {
    return ts ? new Date(ts).toLocaleString('zh-CN', { hour12: false }) : '—';
  }

  /* ---------- 内置流程 ---------- */

  async function loadFlows() {
    try {
      const list = await api('/api/workflow/flows');
      if (!list || !list.length) {
        flowsEl.textContent = '暂无内置流程';
        return;
      }
      flowsEl.className = '';
      flowsEl.innerHTML = '';
      for (const name of list) {
        const btn = document.createElement('button');
        btn.textContent = name;
        btn.style.marginRight = '8px';
        btn.addEventListener('click', () => showFlowDef(name));
        flowsEl.appendChild(btn);
      }
    } catch (e) {
      flowsEl.textContent = '加载失败：' + e.message;
    }
  }

  async function showFlowDef(name) {
    try {
      const data = await api('/api/workflow/flows/' + encodeURIComponent(name));
      flowDefEl.hidden = false;
      flowDefEl.textContent = JSON.stringify(data.flowDef, null, 2);
    } catch (e) {
      alertBox('err', '加载流程定义失败：' + e.message);
    }
  }

  /* ---------- 实例列表 ---------- */

  async function loadInstances() {
    const status = $('#statusSel').value;
    const q = new URLSearchParams({ page: String(page), size: String(SIZE) });
    if (status) q.set('status', status);
    try {
      const data = await api('/api/workflow/instances?' + q.toString());
      renderInstances(data);
    } catch (e) {
      alertBox('err', '加载实例失败：' + e.message);
    }
  }

  function renderInstances(data) {
    const items = data.items || [];
    tb.innerHTML = items.map((it) => {
      const err = it.errorMsg ? '<div class="sub mono">' + esc(it.errorMsg) + '</div>' : '';
      return '<tr><td>' + it.instanceId + '</td><td>' + esc(it.appId) + '</td><td>' + esc(it.flowId)
        + '</td><td>' + tagOf(it.status) + err + '</td><td>' + fmt(it.createdAt)
        + '</td><td>' + fmt(it.finishedAt) + '</td></tr>';
    }).join('');
    emptyEl.hidden = items.length > 0;
    pagerEl.hidden = data.totalPages <= 1;
    $('#pageInfo').textContent = '第 ' + data.page + ' / ' + data.totalPages + ' 页 · 共 ' + data.total + ' 条';
    $('#prevBtn').disabled = data.page <= 1;
    $('#nextBtn').disabled = data.page >= data.totalPages;
  }

  $('#filterForm').addEventListener('submit', (e) => {
    e.preventDefault();
    page = 1;
    loadInstances();
  });
  $('#prevBtn').addEventListener('click', () => { if (page > 1) { page--; loadInstances(); } });
  $('#nextBtn').addEventListener('click', () => { page++; loadInstances(); });

  loadFlows();
  loadInstances();
})();