/* RAG 在线检索页（docs/design/api/20260902-admin-pages.md §2.6）
 * POST /api/rag/search 同步检索：answer + citations + confidenceScore + needsHandoff */
(function () {
  const HDR = { 'X-Tenant-Id': 'default', 'Content-Type': 'application/json' };
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

  async function search() {
    const q = $('#query').value.trim();
    const topK = Math.min(Math.max(parseInt($('#topK').value, 10) || 5, 1), 20);
    const btn = $('#searchForm').querySelector('button');
    btn.disabled = true;
    btn.textContent = '检索中…';
    $('#result').innerHTML = '';
    $('#sources').innerHTML = '';
    try {
      const resp = await fetch('/api/rag/search', {
        method: 'POST',
        headers: HDR,
        body: JSON.stringify({ query: q, topK: topK }),
      });
      const j = await resp.json();
      if (j.code !== 0) throw new Error(j.message || '检索失败');
      render(j.data);
    } catch (e) {
      alertBox('err', '检索失败：' + e.message);
    } finally {
      btn.disabled = false;
      btn.textContent = '检索';
    }
  }

  function render(data) {
    $('#result').innerHTML = '<div class="alert ok" style="white-space:pre-wrap">' + esc(data.answer) + '</div>';

    const bits = ['置信度 ' + (data.confidenceScore == null ? '-' : (+data.confidenceScore).toFixed(2))];
    if (data.citations && data.citations.length) bits.push('引用 ' + data.citations.length + ' 条');
    if (data.needsHandoff) bits.push('⚠ 建议转人工（' + esc(data.handoffReason || '') + '）');
    $('#meta').textContent = bits.join(' · ') + (bits.length ? '\n\n' : '') +
      JSON.stringify({
        answer: data.answer,
        citations: data.citations,
        needsHandoff: data.needsHandoff,
        handoffReason: data.handoffReason,
        confidenceScore: data.confidenceScore,
      }, null, 2);

    if (data.citations && data.citations.length) {
      const box = document.createElement('div');
      const title = document.createElement('h3');
      title.style.cssText = 'margin:14px 0 8px;font-size:14px';
      title.textContent = '📄 引用来源';
      box.appendChild(title);
      data.citations.forEach((c, i) => {
        const el = document.createElement('button');
        el.textContent = c;
        el.style.cssText = 'margin:4px 8px 4px 0';
        el.addEventListener('click', () => {
          const chunk = data.sourceChunks && data.sourceChunks[i];
          alert('来源切片：\n' + (chunk ? chunk.slice(0, 500) : '（无切片内容）'));
        });
        box.appendChild(el);
      });
      $('#sources').appendChild(box);
    }
  }

  $('#searchForm').addEventListener('submit', (e) => { e.preventDefault(); search(); });
})();