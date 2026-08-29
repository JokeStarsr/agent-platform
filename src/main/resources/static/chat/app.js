/* 客服 Web 最小界面（P1 收口 #4，docs/design/api/20260830-audit-chatui.md §2.2）
 * 消费 /api/rag/search/stream 的 SSE（JSON-per-line），按 type 分发渲染打字机/引用/转人工。 */
(function () {
  const form = document.getElementById('form');
  const input = document.getElementById('input');
  const messages = document.getElementById('messages');

  function addUserMsg(text) {
    const el = document.createElement('div');
    el.className = 'msg user';
    el.textContent = text;
    messages.appendChild(el);
    messages.scrollTop = messages.scrollHeight;
  }

  function addBotMsg() {
    const el = document.createElement('div');
    el.className = 'msg bot';
    el.innerHTML = '<div class="text"></div><div class="hint"></div><div class="citations"></div><div class="meta"></div>';
    messages.appendChild(el);
    messages.scrollTop = messages.scrollHeight;
    return {
      el,
      textEl: el.querySelector('.text'),
      hintEl: el.querySelector('.hint'),
      citeEl: el.querySelector('.citations'),
      metaEl: el.querySelector('.meta'),
    };
  }

  function esc(s) {
    const d = document.createElement('div');
    d.textContent = s == null ? '' : String(s);
    return d.innerHTML;
  }

  async function ask(q) {
    const bot = addBotMsg();
    let cursor = document.createElement('span');
    cursor.className = 'cursor';
    bot.textEl.appendChild(cursor);
    const resp = await fetch('/api/rag/search/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': 'default', 'Accept': 'text/event-stream' },
      body: JSON.stringify({ query: q, topK: 5 }),
    });
    if (!resp.ok || !resp.body) throw new Error('请求失败 HTTP ' + resp.status);
    const reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        for (const line of frame.split('\n')) {
          if (!line.startsWith('data:')) continue;
          const evt = JSON.parse(line.slice(5).trim());
          onEvent(evt, bot, cursor);
        }
      }
    }
  }

  function onEvent(evt, bot, cursor) {
    const type = evt.type;
    if (type === 'retrieval') {
      const src = (evt.sources || []).join('、');
      bot.hintEl.textContent = '📚 检索到 ' + evt.chunkCount + ' 份资料' + (src ? '：' + src : '');
    } else if (type === 'answer') {
      cursor.remove();
      bot.textEl.appendChild(document.createTextNode(evt.text));
      cursor = document.createElement('span');
      cursor.className = 'cursor';
      bot.textEl.appendChild(cursor);
      messages.scrollTop = messages.scrollHeight;
    } else if (type === 'done') {
      cursor.remove();
      renderDone(bot, evt);
    } else if (type === 'error') {
      cursor.remove();
      const err = document.createElement('div');
      err.className = 'error';
      err.textContent = '⚠️ ' + evt.message;
      bot.el.appendChild(err);
    }
  }

  function renderDone(bot, evt) {
    if (evt.citations && evt.citations.length) {
      const title = document.createElement('div');
      title.className = 'cite-title';
      title.textContent = '📄 引用来源';
      bot.citeEl.appendChild(title);
      evt.citations.forEach((c) => {
        const a = document.createElement('div');
        a.className = 'cite';
        a.textContent = c;
        a.addEventListener('click', () => {
          const i = evt.citations.indexOf(c);
          const chunk = evt.sourceChunks && evt.sourceChunks[i];
          if (chunk) alert('来源切片：\n' + chunk.slice(0, 500));
        });
        bot.citeEl.appendChild(a);
      });
    }
    const bits = [
      '置信度 ' + (evt.confidenceScore == null ? '-' : (+evt.confidenceScore).toFixed(2)),
      '延迟 ' + evt.latencyMs + 'ms',
      '首Token ' + evt.firstTokenMs + 'ms',
    ];
    bot.metaEl.textContent = bits.join(' · ');
    if (evt.needsHandoff) {
      const btn = document.createElement('button');
      btn.className = 'handoff';
      btn.textContent = '转人工' + (evt.handoffReason ? '（' + evt.handoffReason + '）' : '');
      btn.addEventListener('click', () => { alert('已为您转人工，客服将尽快接入（人工坐席 P2 上线）'); });
      bot.el.appendChild(btn);
    }
    messages.scrollTop = messages.scrollHeight;
  }

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const q = input.value.trim();
    if (!q) return;
    input.value = '';
    addUserMsg(q);
    ask(q).catch((err) => {
      const bot = addBotMsg();
      bot.textEl.textContent = '⚠️ ' + err.message;
    });
  });
})();
