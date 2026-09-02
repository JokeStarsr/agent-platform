/* 应用工厂管理页（docs/design/api/20260902-app-factory.md §2.7）
 * GET /api/apps、GET /{appId}、POST /api/apps、POST /{appId}/start|suspend */
(function () {
  const TENANT = 'default';
  const HDR = { 'X-Tenant-Id': TENANT, 'Content-Type': 'application/json' };
  let page = 1;
  const SIZE = 20;

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

  function tagOf(status) {
    const map = { ENABLED: 'ok', SUSPENDED: 'warn', CREATED: 'idle' };
    return '<span class="tag ' + (map[status] || 'idle') + '">' + esc(status) + '</span>';
  }

  function rowActions(it) {
    let html = '<button data-act="show" data-id="' + esc(it.appId) + '">配置</button>';
    if (it.status === 'SUSPENDED' || it.status === 'CREATED') {
      html += ' <button data-act="start" data-id="' + esc(it.appId) + '">启用</button>';
    } else if (it.status === 'ENABLED') {
      html += ' <button data-act="suspend" data-id="' + esc(it.appId) + '">停用</button>';
    }
    return html;
  }

  async function load() {
    const q = new URLSearchParams({ page: String(page), size: String(SIZE) });
    try {
      const data = await api('/api/apps?' + q.toString());
      const tb = $('#tb');
      const items = data.items || [];
      tb.innerHTML = items.map((it) => '<tr><td>' + esc(it.appId) + '</td><td>' + esc(it.name)
        + '</td><td>' + tagOf(it.status) + '</td><td>v' + it.version + '</td>'
        + '<td><span class="mono">' + esc(it.configJson.length) + 'B</span></td>'
        + '<td>' + rowActions(it) + '</td></tr>').join('');
      $('#empty').hidden = items.length > 0;
      $('#pager').hidden = data.totalPages <= 1;
      $('#pageInfo').textContent = '第 ' + data.page + ' / ' + data.totalPages + ' 页 · 共 ' + data.total + ' 个应用';
      $('#prevBtn').disabled = data.page <= 1;
      $('#nextBtn').disabled = data.page >= data.totalPages;
    } catch (e) {
      alertBox('err', '加载失败：' + e.message);
    }
  }

  $('#createForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const appId = $('#appIdIn').value.trim();
    const name = $('#nameIn').value.trim();
    let configJson = $('#configIn').value.trim();
    try {
      JSON.parse(configJson); // 先验 JSON 语法
    } catch (err) {
      alertBox('err', '配置不是合法 JSON：' + err.message);
      return;
    }
    const btn = $('#createBtn');
    btn.disabled = true;
    try {
      await api('/api/apps', 'POST', JSON.stringify({ appId, name, configJson }));
      alertBox('ok', '应用已创建（CREATED，需启用后才可被消费）');
      $('#configIn').value = '';
      page = 1;
      load();
    } catch (err) {
      alertBox('err', '创建失败：' + err.message);
    } finally {
      btn.disabled = false;
    }
  });

  document.addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const act = btn.dataset.act;
    const id = btn.dataset.id;
    try {
      if (act === 'show') {
        const d = await api('/api/apps/' + encodeURIComponent(id));
        $('#cfgBody').hidden = false;
        $('#cfgBody').textContent = JSON.stringify(JSON.parse(d.configJson), null, 2);
      } else {
        await api('/api/apps/' + encodeURIComponent(id) + '/' + act, 'POST');
        alertBox('ok', (act === 'start' ? '已启用' : '已停用') + '：' + id);
        load();
      }
    } catch (err) {
      alertBox('err', (act === 'show' ? '加载配置失败：' : '操作失败：') + err.message);
    }
  });

  $('#prevBtn').addEventListener('click', () => { if (page > 1) { page--; load(); } });
  $('#nextBtn').addEventListener('click', () => { page++; load(); });

  load();
})();