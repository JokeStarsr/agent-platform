const API = '/api/skill-hub';
const alertBox = document.getElementById('alerts');

function showAlert(type, msg) {
  alertBox.innerHTML = `<div class="alert ${type}">${msg}</div>`;
  setTimeout(() => alertBox.innerHTML = '', 5000);
}

async function req(url, method = 'GET', body) {
  const opt = { method, headers: { 'X-Tenant-Id': 'default', 'Content-Type': 'application/json' } };
  if (body) opt.body = JSON.stringify(body);
  const res = await fetch(url, opt);
  const j = await res.json();
  if (j.code !== 0) throw new Error(j.msg || '请求失败');
  return j.data;
}

const statusMap = { PUBLISHED: '<span class="tag ok">已发布</span>', DRAFT: '<span class="tag warn">草稿</span>', OFF_SHELF: '<span class="tag idle">已下架</span>' };
const enabledMap = { true: '✅', false: '❌' };

async function load() {
  const status = document.getElementById('filterStatus').value;
  const category = document.getElementById('filterCategory').value;
  const params = new URLSearchParams({ page: 1, size: 50 });
  if (status) params.set('status', status);
  if (category) params.set('category', category);
  const data = await req(`${API}?${params}`);
  const tb = document.getElementById('tb');
  tb.innerHTML = '';
  document.getElementById('empty').hidden = data.items.length > 0;
  for (const s of data.items) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${s.id}</td>
      <td class="mono">${s.name}</td>
      <td>${s.displayName}</td>
      <td>${s.category}</td>
      <td>${s.currentVersion}</td>
      <td>${statusMap[s.status] || s.status}</td>
      <td>${enabledMap[s.enabled]}</td>
      <td>
        <button onclick="showDetail(${s.id})">详情</button>
        ${s.status === 'DRAFT' ? `<button class="primary" onclick="publish(${s.id})">发布</button>` : ''}
        ${s.status !== 'OFF_SHELF' ? `<button class="danger" onclick="uninstall(${s.id})">卸载</button>` : ''}
      </td>`;
    tb.appendChild(tr);
  }
}

async function showDetail(id) {
  const d = await req(`${API}/${id}`);
  const skill = d.skill;
  let html = `<p><b>${skill.displayName}</b>（<span class="mono">${skill.name}</span>）· ${skill.category} · v${skill.currentVersion} · ${statusMap[skill.status]}</p>`;
  html += `<p style="font-size:13px;color:#667">${skill.description}</p>`;
  html += `<table><thead><tr><th>版本</th><th>状态</th><th>发布时间</th><th>测试结果</th></tr></thead><tbody>`;
  for (const v of d.versions) {
    html += `<tr><td class="mono">${v.version}</td><td>${v.status}</td><td>${v.releasedAt || '—'}</td><td class="mono">${v.testResult || '—'}</td></tr>`;
  }
  html += `</tbody></table>`;
  document.getElementById('detailCard').hidden = false;
  document.getElementById('detailBody').innerHTML = html;
}

async function publish(id) {
  try {
    const r = await req(`${API}/${id}/publish`, 'POST', {});
    showAlert('ok', `技能「${r.name}」发布成功（测试用例全绿）`);
    load();
  } catch (e) { showAlert('err', e.message); }
}

async function uninstall(id) {
  if (!confirm('确认卸载该技能？')) return;
  try {
    await req(`${API}/${id}/uninstall`, 'POST', {});
    showAlert('ok', '技能已卸载');
    load();
  } catch (e) { showAlert('err', e.message); }
}

document.getElementById('installBtn').addEventListener('click', async () => {
  const manifest = document.getElementById('manifest').value;
  if (!manifest.trim()) return showAlert('err', '请粘贴 manifest JSON');
  try {
    const r = await req(`${API}/install`, 'POST', { manifest, ownerId: 'platform' });
    showAlert('ok', `技能「${r.name}」已安装（DRAFT）`);
    document.getElementById('manifest').value = '';
    load();
    showDetail(r.id);
  } catch (e) { showAlert('err', e.message); }
});

document.getElementById('filterBtn').addEventListener('click', load);

load();