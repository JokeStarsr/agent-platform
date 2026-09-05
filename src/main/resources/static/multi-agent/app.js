/* 多智能体管理页（docs/design/architecture/20260905-multi-agent.md §8）
 * POST /api/multi-agent/runs、GET /runs、GET /runs/{id}、GET /runs/{id}/board */
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
      body: body == null ? undefined : JSON.stringify(body),
    });
    const j = await resp.json().catch(() => ({}));
    if (!resp.ok || j.code !== 0) throw new Error(j.message || ('HTTP ' + resp.status));
    return j.data;
  }

  function tagClass(s) {
    return { COMPLETED: 'ok', RUNNING: 'warn', PLANNING: 'idle', FAILED: 'err', CANCELLED: 'idle' }[s] || 'idle';
  }

  async function load() {
    const status = $('#filterStatus').value;
    const q = new URLSearchParams({ page: '1', size: '20' });
    if (status) q.set('status', status);
    try {
      const data = await api('/api/multi-agent/runs?' + q.toString());
      const items = data.items || [];
      $('#tb').innerHTML = items.map((it) =>
        '<tr><td>' + it.id + '</td><td>' + esc(it.topology) + '</td>' +
        '<td>' + esc(it.task) + '</td>' +
        '<td><span class="tag ' + tagClass(it.status) + '">' + esc(it.status) + '</span></td>' +
        '<td>' + it.totalToken + '</td>' +
        '<td><button data-act="detail" data-id="' + it.id + '">详情+黑板</button></td></tr>'
      ).join('');
      $('#empty').hidden = items.length > 0;
    } catch (e) {
      alertBox('err', '加载失败：' + e.message);
    }
  }

  $('#submitForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const topology = $('#topology').value;
    const task = $('#task').value.trim();
    let stages = null;
    if (topology === 'pipeline') {
      try {
        stages = $('#stages').value.trim() ? JSON.parse($('#stages').value) : null;
        if (!stages) { alertBox('err', 'pipeline 需要填 stages JSON'); return; }
      } catch (err) {
        alertBox('err', 'stages JSON 不合法：' + err.message);
        return;
      }
    }
    try {
      const d = await api('/api/multi-agent/runs', 'POST', { topology, task, stages });
      alertBox('ok', '已提交，rootRunId=' + d.rootRunId);
      $('#task').value = '';
      setTimeout(load, 500);
    } catch (err) {
      alertBox('err', '提交失败：' + err.message);
    }
  });

  document.addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    try {
      const d = await api('/api/multi-agent/runs/' + btn.dataset.id);
      $('#detailCard').hidden = false;
      $('#detailBody').textContent = JSON.stringify(d, null, 2);
    } catch (err) {
      alertBox('err', '详情失败：' + err.message);
    }
  });

  $('#filterBtn').addEventListener('click', load);
  load();
})();