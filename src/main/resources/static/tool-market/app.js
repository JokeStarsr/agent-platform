/* 工具市场管理页（docs/design/architecture/20260905-tool-marketplace.md §7.1）
 * GET /api/tool-market、POST /register、POST /{id}/preview|publish|off-shelf|new-version */
(function () {
  const TENANT = 'default';
  const HDR = { 'X-Tenant-Id': TENANT, 'Content-Type': 'application/json' };
  let page = 1;
  const SIZE = 15;

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
      body: body == null ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
    });
    const j = await resp.json().catch(() => ({}));
    if (!resp.ok || j.code !== 0) throw new Error(j.message || ('HTTP ' + resp.status));
    return j.data;
  }

  function tagClass(status) {
    return { PUBLISHED: 'ok', DRAFT: 'idle', OFF_SHELF: 'warn' }[status] || 'idle';
  }

  function permTag(p) {
    return '<span class="tag ' + (p === 'WRITE' ? 'idle' : 'ok') + '">' + esc(p) + '</span>';
  }

  function actions(it) {
    let html = '';
    if (it.status === 'DRAFT') {
      html += '<button data-act="preview" data-id="' + it.id + '">试调</button>';
      html += ' <button data-act="publish" data-id="' + it.id + '" class="primary">发布</button>';
    }
    if (it.status === 'PUBLISHED') {
      html += ' <button data-act="off-shelf" data-id="' + it.id + '">下架</button>';
    }
    if (it.status !== 'DRAFT') {
      html += ' <button data-act="new-version" data-id="' + it.id +
        '" data-tool="' + esc(it.toolName) + '" data-display="' + esc(it.displayName) +
        '" data-desc="' + esc(it.description) + '" data-category="' + esc(it.category) +
        '" data-perm="' + esc(it.permission) + '">发新版本</button>';
    }
    return html;
  }

  async function load() {
    const status = $('#filterStatus').value;
    const keyword = $('#filterKeyword').value.trim();
    const q = new URLSearchParams({ page: String(page), size: String(SIZE) });
    if (status) q.set('status', status);
    if (keyword) q.set('keyword', keyword);
    try {
      const data = await api('/api/tool-market?' + q.toString());
      const tb = $('#tb');
      const items = data.items || [];
      tb.innerHTML = items.map((it) =>
        '<tr><td class="mono">' + esc(it.toolName) + '</td>' +
        '<td>' + esc(it.displayName) + '</td>' +
        '<td>' + esc(it.category) + '</td>' +
        '<td>v' + it.version + '</td>' +
        '<td>' + permTag(it.permission) + '</td>' +
        '<td>' + esc(it.source) + '</td>' +
        '<td><span class="tag ' + tagClass(it.status) + '">' + esc(it.status) + '</span></td>' +
        '<td>' + actions(it) + '</td></tr>'
      ).join('');
      $('#empty').hidden = items.length > 0;
      $('#pager').hidden = data.totalPages <= 1;
      $('#pageInfo').textContent = '第 ' + data.page + ' / ' + data.totalPages + ' 页 · 共 ' + data.total + ' 个工具';
      $('#prevBtn').disabled = data.page <= 1;
      $('#nextBtn').disabled = data.page >= data.totalPages;
    } catch (e) {
      alertBox('err', '加载目录失败：' + e.message);
    }
  }

  $('#regForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    let testcases = [];
    try { testcases = $('#testcases').value.trim() ? JSON.parse($('#testcases').value) : []; } catch (err) {
      alertBox('err', 'testcases 不是合法 JSON：' + err.message);
      return;
    }
    const body = {
      toolName: $('#toolName').value.trim(),
      displayName: $('#displayName').value.trim(),
      description: $('#description').value.trim(),
      category: $('#category').value,
      parameters: $('#parameters').value.trim(),
      permission: $('#permission').value,
      behavior: $('#behavior').value,
      testcases,
    };
    const btn = e.target.querySelector('button');
    btn.disabled = true;
    try {
      const row = await api('/api/tool-market/register', 'POST', body);
      alertBox('ok', '已注册（DRAFT）：' + row.toolName + '，可试调后发布');
      e.target.reset();
      $('#parameters').value = '{"type":"object","properties":{}}';
      page = 1;
      load();
    } catch (err) {
      alertBox('err', '注册失败：' + err.message);
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
      if (act === 'preview') {
        const out = await api('/api/tool-market/' + id + '/preview', 'POST', {});
        alertBox('ok', '试调结果：' + JSON.stringify(out));
      } else if (act === 'publish') {
        await api('/api/tool-market/' + id + '/publish', 'POST');
        alertBox('ok', '已发布，工具可被 Agent/MCP 调用');
        load();
      } else if (act === 'off-shelf') {
        await api('/api/tool-market/' + id + '/off-shelf', 'POST');
        alertBox('ok', '已下架（从 ToolEngine 移除）');
        load();
      } else if (act === 'new-version') {
        if (!confirm('复制当前工具为 DRAFT v+1，需再次发布才上线。继续？')) return;
        await api('/api/tool-market/' + id + '/new-version', 'POST', {
          toolName: btn.dataset.tool, displayName: btn.dataset.display,
          description: btn.dataset.desc, category: btn.dataset.category,
          parameters: '{"type":"object","properties":{}}', permission: btn.dataset.perm,
          behavior: 'MOCK', testcases: [{ name: '正常', arguments: {}, expectCode: 0 }],
        });
        alertBox('ok', '已创建 v+1 草稿');
        load();
      }
    } catch (err) {
      alertBox('err', '操作失败：' + err.message);
    }
  });

  $('#filterBtn').addEventListener('click', () => { page = 1; load(); });
  $('#prevBtn').addEventListener('click', () => { if (page > 1) { page--; load(); } });
  $('#nextBtn').addEventListener('click', () => { page++; load(); });

  load();
})();