const API = '/api/assistant';
const alertBox = document.getElementById('alerts');
const chatBox = document.getElementById('chatBox');
let sessionId = null;
let pendingPreviewId = null;

function showAlert(type, msg) {
  alertBox.innerHTML = `<div class="alert ${type}">${msg}</div>`;
  setTimeout(() => alertBox.innerHTML = '', 6000);
}

async function req(url, method = 'GET', body) {
  const opt = { method, headers: { 'X-Tenant-Id': 'default', 'Content-Type': 'application/json' } };
  if (body) opt.body = JSON.stringify(body);
  const res = await fetch(url, opt);
  const j = await res.json();
  if (j.code !== 0) throw new Error(j.msg || '请求失败');
  return j.data;
}

function appendMsg(role, text) {
  const div = document.createElement('div');
  div.style.cssText = 'margin:8px 0;padding:10px 12px;border-radius:8px;font-size:13px;white-space:pre-wrap';
  if (role === 'user') {
    div.style.background = '#eff6ff'; div.style.textAlign = 'right';
    div.textContent = '🧑 ' + text;
  } else if (role === 'skill') {
    div.style.background = '#f5f3ff'; div.style.textAlign = 'center';
    div.innerHTML = `🧩 路由到技能：<b>${text}</b>`;
  } else if (role === 'error') {
    div.style.background = '#fef2f2'; div.style.color = '#b91c1c';
    div.textContent = '⚠️ ' + text;
  } else {
    div.style.background = '#f8fafc';
    div.textContent = '🤖 ' + text;
  }
  chatBox.appendChild(div);
  chatBox.scrollTop = chatBox.scrollHeight;
}

async function loadSkills() {
  const skills = await req(`${API}/skills`);
  const box = document.getElementById('skillList');
  box.innerHTML = '';
  if (skills.length === 0) {
    box.innerHTML = '<span style="color:#99a;font-size:13px">暂无可用技能（请先在 /skill-hub/ 发布）</span>';
    return;
  }
  for (const s of skills) {
    const el = document.createElement('div');
    el.style.cssText = 'background:#f8fafc;border:1px solid #e3e8ef;border-radius:8px;padding:8px 10px;font-size:12px';
    el.innerHTML = `<b>${s.displayName}</b><br><span style="color:#667">${s.category} · ${s.orchestration}</span><br>
      <span style="color:#99a">${s.description}</span>`;
    box.appendChild(el);
  }
}

document.getElementById('askForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const message = document.getElementById('message').value.trim();
  const skillHint = document.getElementById('skillHint').value.trim();
  if (!message) return;
  appendMsg('user', message);
  document.getElementById('message').value = '';

  const sendBtn = e.target.querySelector('button');
  sendBtn.disabled = true;
  try {
    const r = await req(`${API}/ask`, 'POST', { message, sessionId, skillHint });
    sessionId = r.sessionId || sessionId;
    if (r.status === 'NO_SKILL') {
      appendMsg('error', r.answer);
    } else if (r.status === 'FAILED' || r.status === 'ERROR') {
      appendMsg('error', r.answer);
    } else {
      if (r.skill) appendMsg('skill', r.skill);
      appendMsg('bot', r.answer || '');
    }
  } catch (err) {
    appendMsg('error', err.message);
  } finally {
    sendBtn.disabled = false;
  }
});

document.getElementById('confirmBtn').addEventListener('click', async () => {
  if (!pendingPreviewId) return;
  try {
    const r = await req(`${API}/confirm`, 'POST', { sessionId, previewId: pendingPreviewId, approved: true });
    appendMsg('bot', '✅ 写操作已执行');
    document.getElementById('previewCard').hidden = true;
    pendingPreviewId = null;
  } catch (e) { showAlert('err', e.message); }
});

document.getElementById('rejectBtn').addEventListener('click', async () => {
  if (!pendingPreviewId) return;
  try {
    const r = await req(`${API}/confirm`, 'POST', { sessionId, previewId: pendingPreviewId, approved: false });
    appendMsg('bot', '❌ 已拒绝该写操作');
    document.getElementById('previewCard').hidden = true;
    pendingPreviewId = null;
  } catch (e) { showAlert('err', e.message); }
});

loadSkills();