/* MCP 网关管理页（docs/design/architecture/20260904-mcp-gateway.md §7.2）
 * GET /api/mcp/tools、GET /api/mcp/grants?tenantId=、POST /api/mcp/grants、GET /api/mcp/health */
(function () {
  const TENANT = 'default';
  const HDR = { 'X-Tenant-Id': TENANT, 'Content-Type': 'application/json' };

  const $ = (s) => document.querySelector(s);

  function esc(s) {
    const d = document.createElement('div');
    d.textContent = s == null ? '' : String(s);
    return d.innerHTML;
  }

  function alertBox(type, msg) {
    const el = document.createElement('div');
    el.className = 'alert ' + type;
    el.textContent = msg;
    $('#alerts').appendChild(el);
    setTimeout(() => el.remove(), 8000);
  }

  async function api(url, method, body) {
    const resp = await fetch(url, {
      method: method || 'GET',
      headers: HDR,
      body: body == null ? undefined : body,
    });
    const j = await resp.json().catch(() => ({}));
    if (!resp.ok || j.code !== 0) throw new Error(j.message || ('HTTP ' + resp.status));
    return j.data;
  }

  function permTag(p) {
    const cls = p === 'PAYMENT' ? 'warn' : p === 'WRITE' ? 'idle' : 'ok';
    return '<span class="tag ' + cls + '">' + esc(p) + '</span>';
  }

  async function loadHealth() {
    try {
      const h = await api('/api/mcp/health');
      const statusCls = h.status === 'UP' ? 'ok' : 'warn';
      $('#health').innerHTML =
        '<span class="tag ' + statusCls + '">' + esc(h.status) + '</span> ' +
        'Server: <strong>' + esc(h.server) + '</strong> · ' +
        '工具数: <strong>' + h.toolCount + '</strong> · ' +
        '运行: ' + Math.round(h.uptimeMs / 1000) + 's';
    } catch (e) {
      $('#health').textContent = '健康检查失败：' + e.message;
    }
  }

  async function loadTools() {
    try {
      const tools = await api('/api/mcp/tools');
      const tb = $('#toolTb');
      tb.innerHTML = tools.map((t) =>
        '<tr><td class="mono">' + esc(t.name) + '</td>' +
        '<td>' + esc(t.description) + '</td>' +
        '<td>' + permTag(t.permission) + '</td>' +
        '<td>' + (t.timeoutMs / 1000) + 's</td></tr>'
      ).join('');
      $('#toolEmpty').hidden = tools.length > 0;
    } catch (e) {
      alertBox('err', '加载工具列表失败：' + e.message);
    }
  }

  async function loadGrants(tenantId) {
    try {
      const grants = await api('/api/mcp/grants?tenantId=' + encodeURIComponent(tenantId));
      const tb = $('#grantTb');
      tb.innerHTML = grants.map((g) =>
        '<tr><td class="mono">' + esc(g.toolName) + '</td>' +
        '<td>' + permTag(g.permission) + '</td>' +
        '<td>' + (g.enabled ? '<span class="tag ok">已授权</span>' : '<span class="tag warn">已撤销</span>') + '</td>' +
        '<td><button data-act="toggle" data-tenant="' + esc(g.tenantId) +
        '" data-tool="' + esc(g.toolName) +
        '" data-enabled="' + (!g.enabled) + '">' +
        (g.enabled ? '撤销' : '重新授权') + '</button></td></tr>'
      ).join('');
      $('#grantEmpty').hidden = grants.length > 0;
    } catch (e) {
      alertBox('err', '加载授权列表失败：' + e.message);
    }
  }

  $('#loadGrants').addEventListener('click', () => {
    const t = $('#grantTenant').value.trim() || 'default';
    loadGrants(t);
  });

  $('#grantForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const tenantId = $('#gTenant').value.trim();
    const toolName = $('#gTool').value.trim();
    const enabled = $('#gEnabled').value === 'true';
    try {
      await api('/api/mcp/grants', 'POST', JSON.stringify({ tenantId, toolName, enabled }));
      alertBox('ok', (enabled ? '已授权' : '已撤销') + '：' + toolName + ' → ' + tenantId);
      $('#gTool').value = '';
      loadGrants(tenantId);
      loadHealth();
    } catch (err) {
      alertBox('err', '操作失败：' + err.message);
    }
  });

  document.addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-act="toggle"]');
    if (!btn) return;
    const tenant = btn.dataset.tenant;
    const tool = btn.dataset.tool;
    const enabled = btn.dataset.enabled === 'true';
    try {
      await api('/api/mcp/grants', 'POST', JSON.stringify({ tenantId: tenant, toolName: tool, enabled }));
      alertBox('ok', (enabled ? '已授权' : '已撤销') + '：' + tool);
      loadGrants(tenant);
    } catch (err) {
      alertBox('err', '操作失败：' + err.message);
    }
  });

  loadHealth();
  loadTools();
  loadGrants(TENANT);
})();
