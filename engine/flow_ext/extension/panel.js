// Bảng quản lý cạnh phải: đọc trạng thái background.js lưu trong chrome.storage, tự cập nhật khi có thay đổi.
const FLOW_URLS = ['https://flow.google.com/*', 'https://labs.google/fx/tools/flow*', 'https://labs.google/fx/*/tools/flow*'];
const $ = (id) => document.getElementById(id);

function pill(id, cls, text) {
  const el = $(id);
  el.className = `pill ${cls}`;
  el.querySelector('em').textContent = text;
}

const hhmm = (t) => new Date(t).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

function elapsed(from) {
  const s = Math.max(0, Math.round((Date.now() - from) / 1000));
  return s < 60 ? `${s} giây` : `${Math.floor(s / 60)} phút ${s % 60} giây`;
}

let current = null;
let flowTabs = [];

async function refreshTabs() {
  flowTabs = await chrome.tabs.query({ url: FLOW_URLS });
  render();
}

function render() {
  const s = current || {};
  $('ver').textContent = `Phiên bản ${chrome.runtime.getManifest().version}`;
  pill('app', s.connected ? 'ok' : 'err', s.connected ? 'Đã kết nối' : 'Chưa kết nối');
  pill('acc', s.email ? 'ok' : 'warn', s.email || 'Chưa đọc được: mở tab Flow đã đăng nhập');

  const project = flowTabs.find((t) => t.url && t.url.includes('/project/'));
  if (!flowTabs.length) pill('tab', 'err', 'Chưa mở');
  else if (project) pill('tab', 'ok', 'Đang mở dự án');
  else pill('tab', 'warn', 'Đang mở (chưa vào dự án)');

  pill('ctl', s.attached ? 'run' : '', s.attached ? 'Đang điều khiển' : 'Không');

  const hints = [];
  if (!s.connected) hints.push('Mở app <b>Flow Batch Studio</b> trên máy. Extension tự nối lại mỗi 5 giây.');
  if (!flowTabs.length) hints.push('Bấm <b>Mở tab Flow</b> và đăng nhập Google.');
  if (s.attached) hints.push('Đừng bấm hay gõ trên tab Flow khi app đang điền. Dải "đang gỡ lỗi" của Chrome là bình thường.');
  $('hint').innerHTML = hints.join('<br>');

  const job = s.job;
  if (job) {
    const mode = job.mode === 'FOREGROUND' ? 'Mở Flow (bạn bấm Tạo)' : 'Chạy ngầm';
    $('job').innerHTML = '';
    const title = document.createElement('div');
    title.className = 'row';
    title.innerHTML = '<span class="job-title"></span><span class="pill run"><i class="dot"></i><em></em></span>';
    title.querySelector('.job-title').textContent = job.label || 'Cảnh';
    title.querySelector('em').textContent = elapsed(job.startedAt);
    const sub = document.createElement('div');
    sub.className = 'hint';
    sub.textContent = mode;
    const step = document.createElement('div');
    step.className = 'job-step';
    step.textContent = job.step || '…';
    $('job').append(title, sub, step);
  } else {
    $('job').innerHTML = '<div class="empty">Không có cảnh nào. Bấm Chạy trong app để bắt đầu.</div>';
  }

  $('done').textContent = s.stats?.done || 0;
  $('failed').textContent = s.stats?.failed || 0;
  const log = s.log || [];
  $('dl').textContent = log.filter((e) => e.text.startsWith('Chrome tải xong')).length;

  const ul = $('log');
  ul.innerHTML = '';
  if (!log.length) {
    ul.innerHTML = '<li><time></time><span class="empty">Chưa có gì.</span></li>';
  }
  for (const e of log.slice(0, 100)) {
    const li = document.createElement('li');
    li.className = e.level;
    const t = document.createElement('time');
    t.textContent = hhmm(e.t);
    const sp = document.createElement('span');
    sp.textContent = e.text;
    li.append(t, sp);
    ul.append(li);
  }
}

chrome.storage.local.get('bridgeState').then(({ bridgeState }) => { current = bridgeState; render(); });
chrome.storage.onChanged.addListener((changes) => {
  if (changes.bridgeState) { current = changes.bridgeState.newValue; render(); }
});
chrome.tabs.onUpdated.addListener(refreshTabs);
chrome.tabs.onRemoved.addListener(refreshTabs);
refreshTabs();
setInterval(render, 1000);   // đồng hồ thời gian chạy của cảnh

$('openFlow').onclick = () => chrome.runtime.sendMessage({ type: 'OPEN_FLOW_TAB' });
$('reconnect').onclick = () => chrome.runtime.sendMessage({ type: 'RECONNECT' });
$('clear').onclick = () => chrome.runtime.sendMessage({ type: 'CLEAR_LOG' });
