/* 知识库管理页（docs/design/api/20260902-admin-pages.md §2.6）
 * GET /api/rag/collections 统计、POST /api/rag/index 上传、DELETE /api/rag/collections 清空 */
(function () {
  const TENANT = 'default';
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

  function statCard(label, value) {
    return '<div class="card" style="margin:0"><h3 style="font-size:13px;color:#667">' + label
      + '</h3><div style="font-size:26px;font-weight:600">' + esc(value) + '</div></div>';
  }

  async function loadStats() {
    try {
      const resp = await fetch('/api/rag/collections', { headers: { 'X-Tenant-Id': TENANT } });
      const j = await resp.json();
      if (j.code !== 0) throw new Error(j.message || '接口错误');
      const d = j.data;
      $('#statGrid').innerHTML =
        statCard('切片数 chunkCount', d.chunkCount) +
        statCard('文档数 docCount', d.docCount) +
        statCard('最近入库', d.lastUpdate ? new Date(d.lastUpdate).toLocaleString('zh-CN', { hour12: false }) : '—');
    } catch (e) {
      $('#statGrid').innerHTML = '<div class="empty">加载失败：' + esc(e.message) + '</div>';
    }
  }

  $('#uploadForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const file = $('#fileInput').files[0];
    if (!file) return;
    const btn = e.target.querySelector('button');
    btn.disabled = true;
    btn.textContent = '上传中…';
    try {
      const fd = new FormData();
      fd.append('file', file);
      const resp = await fetch('/api/rag/index', {
        method: 'POST',
        headers: { 'X-Tenant-Id': TENANT },
        body: fd,
      });
      const j = await resp.json();
      if (j.code !== 0) throw new Error(j.message || '入库失败');
      alertBox('ok', '入库成功：' + j.data + ' 个切片');
      loadStats();
    } catch (err) {
      alertBox('err', '上传失败：' + err.message);
    } finally {
      btn.disabled = false;
      btn.textContent = '上传并索引';
    }
  });

  $('#clearBtn').addEventListener('click', async () => {
    if (!confirm('确认清空当前租户（' + TENANT + '）的全部知识库切片？此操作不可恢复。')) return;
    try {
      const resp = await fetch('/api/rag/collections', { method: 'DELETE', headers: { 'X-Tenant-Id': TENANT } });
      const j = await resp.json();
      if (j.code !== 0) throw new Error(j.message || '清空失败');
      alertBox('ok', '知识库已清空');
      loadStats();
    } catch (err) {
      alertBox('err', '清空失败：' + err.message);
    }
  });

  loadStats();
})();