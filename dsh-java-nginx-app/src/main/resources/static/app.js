const state = {
  conversations: [],
  activeConversationId: null,
  activeStream: null
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------

function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations();
  renderConversations();
  renderChatMessages();
}

function saveConversations() {
  localStorage.setItem('dsh.nginxcomposer.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}

function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.nginxcomposer.conversations') || '[]'); }
  catch { state.conversations = []; }
}

function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => {
      state.activeConversationId = conversation.id;
      renderConversations();
      renderChatMessages();
    };
    list.appendChild(button);
  });
}

function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') {
      div.className = 'msg user';
      div.textContent = message.content.length > 2000 ? message.content.slice(0, 2000) + '…' : message.content;
    } else {
      div.className = 'msg assistant';
      div.innerHTML = assistantHtml(message);
    }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}

function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- nginx panels ----------

const currentConf = () => $('#confInput').value;

const levelBadge = l => l === 'warn' ? '<span class="badge warn">warn</span>' : '<span class="badge info">info</span>';

async function runParse() {
  const conf = currentConf();
  if (!conf.trim()) return toast('先粘贴 nginx conf', true);
  const data = await request('/api/nginx/parse', { method: 'POST', body: JSON.stringify({ conf }) });
  $('#parseResult').innerHTML =
    `<div class="small muted">${data.serverCount} 个 server 块</div>` +
    (data.servers || []).map(s => `
      <div class="branch-row">
        <b class="small">${escapeHtml(String(s.serverName))}</b>
        <span class="badge info">:${escapeHtml(String(s.listen))}</span>
        ${(s.locations || []).map(l => `<div class="muted small">${escapeHtml(String(l.path))} → ${escapeHtml(String(l.type))} ${escapeHtml(String(l.target || ''))}</div>`).join('')}
      </div>`).join('') || '<div class="muted small">未发现 server 块</div>';
  return data;
}

async function runRoutes() {
  const conf = currentConf();
  if (!conf.trim()) return toast('先粘贴 nginx conf', true);
  const data = await request('/api/nginx/routes', { method: 'POST', body: JSON.stringify({ conf }) });
  $('#routesResult').innerHTML =
    `<div class="small muted">${data.routeCount} 条路由 · ${data.duplicateCount} 处重复</div>` +
    (data.routes || []).map(rt => `
      <div class="branch-row">
        <span class="badge info">${escapeHtml(String(rt.host))}</span>
        <b class="small">${escapeHtml(String(rt.path))}</b>
        <div class="muted small">${escapeHtml(String(rt.type))} → ${escapeHtml(String(rt.target || '-'))}</div>
      </div>`).join('') +
    (data.duplicates || []).map(d => `<div class="branch-row"><span class="badge warn">重复</span><span class="small">${escapeHtml(String(d.route))}</span></div>`).join('');
  return data;
}

async function runLint() {
  const conf = currentConf();
  if (!conf.trim()) return toast('先粘贴 nginx conf', true);
  const data = await request('/api/nginx/lint', { method: 'POST', body: JSON.stringify({ conf }) });
  $('#lintResult').innerHTML =
    `<div class="small muted">${data.issueCount} 个问题（以 nginx -t 实测为准）</div>` +
    (data.issues || []).map(i => `
      <div class="branch-row">
        ${levelBadge(i.level)}
        <div class="small">${i.line ? 'L' + i.line + ' ' : ''}${escapeHtml(String(i.msg))}</div>
      </div>`).join('') || '<div class="muted small">✓ 未发现问题</div>';
  document.querySelector('[data-resource-tab="lint"]').click();
  return data;
}

async function runGen() {
  const intent = $('#genIntent').value;
  const target = $('#genTarget').value.trim();
  const path = $('#genPath').value.trim();
  if (!target) return toast('输入目标地址/目录', true);
  const data = await request('/api/nginx/gen-location', { method: 'POST', body: JSON.stringify({ intent, target, path }) });
  $('#genResult').innerHTML = `<pre style="font-size:12px;white-space:pre-wrap;margin:0">${escapeHtml(String(data.snippet))}</pre>`;
  return data;
}

async function runTls(domain) {
  const data = await request('/api/nginx/tls-checklist', { method: 'POST', body: JSON.stringify({ domain }) });
  $('#genResult').innerHTML =
    `<div class="small muted">${escapeHtml(String(domain))} TLS checklist</div>` +
    (data.checklist || []).map(c => `<div class="branch-row"><div class="small">${escapeHtml(c)}</div></div>`).join('') +
    `<pre style="font-size:12px;white-space:pre-wrap;margin:8px 0 0">${escapeHtml(String(data.snippet))}</pre>`;
  document.querySelector('[data-resource-tab="gen"]').click();
  return data;
}

// ---------- chat ----------

async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations();
  renderConversations();
  renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations();
  renderChatMessages();
}

async function streamAssistant(message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = '';
  let finished = false;
  let renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------

$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    sendMessage();
  }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };
$('#openTls').onclick = () => $('#tlsModal').classList.remove('hidden');
$('#cancelTlsModal').onclick = () => $('#tlsModal').classList.add('hidden');
$('#tlsForm').onsubmit = async event => {
  event.preventDefault();
  const domain = new FormData(event.target).get('domain');
  try {
    await runTls(domain);
    $('#tlsModal').classList.add('hidden');
    event.target.reset();
    toast(`已生成 ${domain} TLS 清单`);
  } catch (error) {
    toast(error.message, true);
  }
};
$('#parseBtn').onclick = () => runParse().catch(e => toast(e.message, true));
$('#routesBtn').onclick = () => runRoutes().catch(e => toast(e.message, true));
$('#lintBtn').onclick = () => runLint().catch(e => toast(e.message, true));
$('#genBtn').onclick = () => runGen().catch(e => toast(e.message, true));
$('#refreshResource').onclick = async () => {
  toast('工作台为即时分析，无需刷新');
};
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.nginxcomposer.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.nginxcomposer.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

// ---------- init ----------

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations();
  renderChatMessages();
})();
