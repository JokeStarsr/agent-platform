/* 三级记忆演示页（docs/design/api/20260902-admin-pages.md §2.6）
 * GET /api/memory/demo、/short、/user/{userId}、/pending、/org；POST /short、/long、/{id}/confirm */
(function () {
  const TENANT = 'default';
  const OPERATOR = 'u_1001'; // 页面演示操作者（长期记忆归属校验用）
  const HDR = { 'X-Tenant-Id': TENANT, 'X-User-Id': OPERATOR, 'Content-Type': 'application/json' };
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

  function kvList(list) {
    if (!list || !list.length) return '<div class="empty">暂无数据</div>';
    return '<table><thead><tr><th>字段</th><th>值</th><th>来源/时间</th></tr></thead><tbody>'
      + list.map((it) => '<tr><td>' + esc(it.field || it.role || it.id) + '</td><td>' + esc(it.value || it.content)
        + '</td><td>' + esc(it.source || it.createdAt || it.updatedAt || '') + '</td></tr>').join('')
      + '</tbody></table>';
  }

  async function loadDemo() {
    try {
      const d = await api('/api/memory/demo');
      $('#demoBody').textContent = JSON.stringify(d, null, 2);
    } catch (e) {
      $('#demoBody').textContent = '加载失败：' + e.message;
    }
  }

  $('#shortQForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      const sid = $('#sessionIdQ').value.trim();
      const list = await api('/api/memory/short?sessionId=' + encodeURIComponent(sid));
      $('#shortOut').innerHTML = kvList(list);
    } catch (err) { alertBox('err', '短期记忆查询失败：' + err.message); }
  });

  $('#shortWForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await api('/api/memory/short', 'POST', {
        sessionId: $('#sessionIdW').value.trim(),
        role: $('#roleW').value.trim(),
        content: $('#contentW').value.trim(),
      });
      alertBox('ok', '短期记忆已写入');
      $('#contentW').value = '';
    } catch (err) { alertBox('err', '写入失败：' + err.message); }
  });

  $('#longQForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      const uid = $('#userIdQ').value.trim();
      const q = $('#queryQ').value.trim();
      const url = '/api/memory/user/' + encodeURIComponent(uid) + (q ? '?query=' + encodeURIComponent(q) : '');
      const list = await api(url);
      $('#longOut').innerHTML = kvList(list);
    } catch (err) { alertBox('err', '长期记忆查询失败：' + err.message); }
  });

  $('#longWForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      await api('/api/memory/long', 'POST', {
        userId: $('#userIdW').value.trim(),
        field: $('#fieldW').value.trim(),
        value: $('#valueW').value.trim(),
        source: 'console',
      });
      alertBox('ok', '长期记忆已写入（可去待确认列表审批）');
      $('#valueW').value = '';
    } catch (err) { alertBox('err', '写入失败：' + err.message); }
  });

  $('#pendingForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      const uid = $('#pendingUserId').value.trim();
      const list = await api('/api/memory/pending?userId=' + encodeURIComponent(uid));
      const out = $('#pendingOut');
      if (!list || !list.length) { out.innerHTML = '<div class="empty">无待确认项</div>'; return; }
      out.innerHTML = '<table><thead><tr><th>id</th><th>字段</th><th>值</th><th>操作</th></tr></thead><tbody>'
        + list.map((it) => '<tr><td>' + it.id + '</td><td>' + esc(it.field) + '</td><td>' + esc(it.value)
          + '</td><td><button data-confirm="' + it.id + '" data-acc="true">接受</button> '
          + '<button data-confirm="' + it.id + '" data-acc="false">拒绝</button></td></tr>').join('')
        + '</tbody></table>';
    } catch (err) { alertBox('err', '待确认查询失败：' + err.message); }
  });

  document.addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-confirm]');
    if (!btn) return;
    try {
      await api('/api/memory/' + btn.dataset.confirm + '/confirm?accept=' + btn.dataset.acc, 'POST');
      alertBox('ok', '确认已提交');
      $('#pendingForm').dispatchEvent(new Event('submit'));
    } catch (err) { alertBox('err', '确认失败：' + err.message); }
  });

  $('#orgForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
      const q = $('#orgQuery').value.trim();
      const d = await api('/api/memory/org?query=' + encodeURIComponent(q));
      $('#orgOut').innerHTML = '<pre class="json">' + esc(JSON.stringify(d, null, 2)) + '</pre>';
    } catch (err) { alertBox('err', '组织记忆搜索失败：' + err.message); }
  });

  loadDemo();
})();