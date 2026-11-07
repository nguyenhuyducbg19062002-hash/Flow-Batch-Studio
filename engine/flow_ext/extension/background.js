// Flow Batch Studio – Flow Bridge (service worker)
// Nối tới app trên máy qua ws://127.0.0.1:18321, nhận lệnh batch_rpc và chạy nó trong tab flow.google.com đã đăng nhập.
// Cách chạy lệnh trong tab và lấy reCAPTCHA tham khảo FlowKit (https://github.com/crisng95/flowkit, MIT).

const APP_WS_URL = 'ws://127.0.0.1:18321';
const FLOW_URLS = ['https://flow.google.com/*', 'https://labs.google/fx/tools/flow*', 'https://labs.google/fx/*/tools/flow*'];
const FLOW_TAB_URL = 'https://flow.google.com/';
const CAPTCHA_SLOT = '__CAPTCHA__';
const MAX_RPC_TEXT = 32000000;

let ws = null;
// Trạng thái hiện trên bảng quản lý (panel.html). job = cảnh app đang chạy; log = nhật ký mới nhất ở đầu.
let state = { connected: false, lastError: null, ok: 0, failed: 0, lastRpc: null, attached: false, tabUrl: null,
              job: null, stats: { done: 0, failed: 0 }, log: [] };
const MAX_LOG = 200;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function saveState(patch) {
  state = { ...state, ...patch };
  chrome.storage.local.set({ bridgeState: state });
  const busy = state.connected && state.job;
  chrome.action.setBadgeText({ text: !state.connected ? '' : busy ? 'RUN' : 'ON' });
  chrome.action.setBadgeBackgroundColor({ color: !state.connected ? '#64748b' : busy ? '#4f46e5' : '#16a34a' });
}

function addLog(text, level = 'info', extra = {}) {
  const entry = { t: Date.now(), text, level };
  saveState({ ...extra, log: [entry, ...(state.log || [])].slice(0, MAX_LOG) });
}

// service worker khởi động lại: giữ nhật ký / thống kê cũ, còn kết nối thì tính lại từ đầu
chrome.storage.local.get('bridgeState').then(({ bridgeState }) => {
  if (!bridgeState) return;
  state = { ...state, log: bridgeState.log || [], stats: bridgeState.stats || state.stats, ok: bridgeState.ok || 0,
            failed: bridgeState.failed || 0 };
});

// ── cảnh app đang chạy (bridge chuyển tiến độ từ worker sang) ─────────────────

function handleJob(msg) {
  const label = msg.label || `Cảnh ${msg.jobId ?? ''}`;
  if (msg.event === 'start') {
    addLog(`▶ ${label}: bắt đầu (${msg.text || ''})`, 'info',
           { job: { jobId: msg.jobId, label, kind: msg.kind, mode: msg.mode, step: 'Bắt đầu', startedAt: Date.now() } });
  } else if (msg.event === 'step') {
    const job = state.job && state.job.jobId === msg.jobId ? { ...state.job, step: msg.text } : state.job;
    addLog(`${label}: ${msg.text}`, 'info', { job });
  } else if (msg.event === 'done') {
    addLog(`✓ ${label}: xong. ${msg.text || ''}`, 'ok',
           { job: null, stats: { ...state.stats, done: (state.stats?.done || 0) + 1 } });
  } else if (msg.event === 'fail') {
    addLog(`✗ ${label}: ${msg.text || 'lỗi'}`, 'error',
           { job: null, stats: { ...state.stats, failed: (state.stats?.failed || 0) + 1 } });
  }
}

// ── kết nối tới app ──────────────────────────────────────────────────────────

function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  try {
    ws = new WebSocket(APP_WS_URL);
  } catch (e) {
    saveState({ connected: false });
    return;
  }
  ws.onopen = async () => {
    addLog('Đã kết nối app Flow Batch Studio', 'ok', { connected: true, lastError: null });
    const id = await loadIdentity();
    send({ type: 'extension_ready', extensionVersion: chrome.runtime.getManifest().version, ...id });
    refreshIdentity();
  };
  ws.onclose = () => {
    ws = null;
    if (state.connected) addLog('Mất kết nối app (app tắt?): tự nối lại mỗi 5 giây', 'warn', { connected: false, job: null });
    else saveState({ connected: false });
    setTimeout(connect, 5000);
  };
  ws.onerror = () => {};
  ws.onmessage = async (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    if (msg.type === 'pong') return;
    if (msg.type === 'job') { handleJob(msg); return; }
    if (msg.method === 'batch_rpc') handleBatchRpc(msg);
    else if (msg.method === 'cdp') handleCdp(msg);
    else if (msg.method === 'cdp_attach') reply(msg.id, cdpAttach);
    else if (msg.method === 'cdp_detach') reply(msg.id, cdpDetach);
  };
}

async function reply(id, fn) {
  try {
    send({ id, result: await fn() });
  } catch (e) {
    send({ id, error: e?.message || String(e) });
  }
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

// Chrome tắt service worker rảnh sau ~30 giây; WebSocket có trao đổi đều đặn giữ nó sống (Chrome 116+).
// Alarm (tối thiểu 30 giây) đánh thức lại service worker nếu nó vẫn bị tắt, rồi nối lại.
setInterval(() => send({ type: 'ping' }), 20000);
chrome.alarms.create('reconnect', { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener((a) => {
  if (a.name === 'reconnect') { connect(); send({ type: 'ping' }); refreshIdentity(); }
});

// ── danh tính: hồ sơ Chrome này (clientId) + email Google đang đăng nhập Flow ─────
// Mỗi tài khoản Google dùng một hồ sơ Chrome riêng có cài extension. App nhận nhiều extension cùng lúc, tự thêm tài khoản
// theo email và giao cảnh của tài khoản nào cho đúng Chrome của tài khoản đó.
let identity = null;

async function loadIdentity() {
  if (identity) return identity;
  const { bridgeClientId, bridgeEmail } = await chrome.storage.local.get(['bridgeClientId', 'bridgeEmail']);
  let clientId = bridgeClientId;
  if (!clientId) {
    clientId = (crypto.randomUUID && crypto.randomUUID()) || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    await chrome.storage.local.set({ bridgeClientId: clientId });
  }
  identity = { clientId, email: bridgeEmail || null };
  if (identity.email && state.email !== identity.email) saveState({ email: identity.email });
  return identity;
}

// Email trên huy hiệu tài khoản của trang Flow: aria-label "Google Account: Tên (email)" / "Tài khoản Google: …"
async function detectEmail() {
  const tabs = await chrome.tabs.query({ url: FLOW_URLS });
  for (const t of tabs) {
    if (t.discarded || t.status !== 'complete') continue;
    try {
      const [res] = await chrome.scripting.executeScript({
        target: { tabId: t.id },
        func: () => {
          const re = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
          for (const el of document.querySelectorAll('[aria-label]')) {
            const a = el.getAttribute('aria-label') || '';
            if (!/google/i.test(a)) continue;
            const m = a.match(re);
            if (m) return m[0].toLowerCase();
          }
          return null;
        },
      });
      if (res?.result) return res.result;
    } catch {}
  }
  return null;
}

let identityBusy = false;
async function refreshIdentity() {
  if (identityBusy) return;
  identityBusy = true;
  try {
    const id = await loadIdentity();
    const email = await detectEmail();   // không có tab Flow / chưa tải xong: giữ email đã biết
    if (email && email !== id.email) {
      id.email = email;
      await chrome.storage.local.set({ bridgeEmail: email });
      addLog(`Tài khoản Google trên Flow: ${email}`, 'info', { email });
      send({ type: 'identity', clientId: id.clientId, email });
    }
  } finally {
    identityBusy = false;
  }
}

chrome.tabs.onUpdated.addListener((_id, info, tab) => {
  if (info.status === 'complete' && /^https:\/\/(flow\.google\.com|labs\.google\/fx)\//.test(tab.url || '')) {
    setTimeout(refreshIdentity, 3000);   // huy hiệu tài khoản hiện sau khi trang vẽ xong
  }
});
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
connect();

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg?.type === 'GET_STATE') { reply(state); return; }
  if (msg?.type === 'RECONNECT') { connect(); reply(state); return; }
  if (msg?.type === 'OPEN_FLOW_TAB') { openFlowTab().then(() => reply(true)); return true; }
  if (msg?.type === 'CLEAR_LOG') { saveState({ log: [], stats: { done: 0, failed: 0 }, ok: 0, failed: 0 }); reply(true); return; }
});

// ── tab Flow ────────────────────────────────────────────────────────────────

async function reviveTabIfNeeded(tab) {
  if (!tab?.discarded) return tab;
  try {
    await chrome.tabs.reload(tab.id);
    await sleep(2500);
    return await chrome.tabs.get(tab.id);
  } catch {
    return null;
  }
}

async function pickFlowTab() {
  const tabs = await chrome.tabs.query({ url: FLOW_URLS });
  let tab =
    tabs.find((t) => t.url && t.url.includes('/project/') && !t.discarded) ||
    tabs.find((t) => t.active && !t.discarded) ||
    tabs.find((t) => !t.discarded) ||
    tabs[0];
  if (!tab) {
    // Tab Flow bị đóng: mở lại. Chrome không còn cửa sổ nào (tabs.create báo "No current window") thì mở cửa sổ mới.
    let opened = null;
    try {
      opened = await chrome.tabs.create({ url: FLOW_TAB_URL, active: false });
    } catch {
      const win = await chrome.windows.create({ url: FLOW_TAB_URL, focused: false, state: 'normal' });
      opened = win?.tabs?.[0] || null;
    }
    if (opened) addLog('Đã mở lại tab Flow', 'info');
    await sleep(5000);
    tab = opened ? await chrome.tabs.get(opened.id).catch(() => null) : null;
  }
  return reviveTabIfNeeded(tab);
}

// ── reCAPTCHA ───────────────────────────────────────────────────────────────

async function requestCaptchaFromTab(tabId, requestId, pageAction) {
  const msg = { type: 'GET_CAPTCHA', requestId, pageAction };
  try {
    return await chrome.tabs.sendMessage(tabId, msg);
  } catch (error) {
    const m = error?.message || '';
    if (!m.includes('Receiving end does not exist') && !m.includes('Could not establish connection')) throw error;
    await chrome.scripting.executeScript({ target: { tabId }, files: ['content.js'] });
    await sleep(200);
    return await chrome.tabs.sendMessage(tabId, msg);
  }
}

function captchaFromTab(tabId, requestId, action) {
  return Promise.race([
    requestCaptchaFromTab(tabId, requestId, action),
    new Promise((_, rej) => setTimeout(() => rej(new Error('CAPTCHA_TIMEOUT')), 30000)),
  ]);
}

// ── chạy một lệnh batchexecute trong tab ────────────────────────────────────

async function runBatchRpc(cmd) {
  const tab = await pickFlowTab();
  if (!tab) return { error: 'NO_FLOW_TAB' };

  let freq = cmd.freq;
  if (cmd.captchaAction) {
    const solved = await captchaFromTab(tab.id, cmd.id, cmd.captchaAction);
    if (!solved?.token) return { error: `CAPTCHA_FAILED: ${solved?.error || 'no token'}` };
    freq = freq.split(CAPTCHA_SLOT).join(solved.token);
  }

  const [injected] = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    world: 'MAIN',
    args: [cmd.rpcid, freq, MAX_RPC_TEXT, cmd.match || null],
    func: async (rpcid, freqStr, maxText, match) => {
      const wiz = globalThis.WIZ_global_data || {};
      const at = wiz.SNlM0e;
      if (!at) return { error: 'NO_AT_TOKEN' };
      const reqid = Math.floor(Math.random() * 900000) + 100000;
      const hl = (document.documentElement.lang || navigator.language || 'en').split('-')[0];
      const url = `/_/AiSandboxAngularFrontend/data/batchexecute?rpcids=${encodeURIComponent(rpcid)}` +
        `&source-path=${encodeURIComponent(location.pathname || '/')}` +
        `&bl=${encodeURIComponent(wiz.cfb2h || '')}&f.sid=${encodeURIComponent(wiz.FdrFJe || '')}` +
        `&hl=${encodeURIComponent(hl)}&_reqid=${reqid}&rt=c`;
      const resp = await fetch(url, {
        method: 'POST',
        credentials: 'include',
        headers: { 'content-type': 'application/x-www-form-urlencoded;charset=UTF-8', 'x-same-domain': '1' },
        body: new URLSearchParams({ 'f.req': freqStr, at }),
      });
      const text = await resp.text();
      if (match) {
        const i = text.toLowerCase().indexOf(match.toLowerCase());
        if (i === -1) return { status: resp.status, text: '' };
        return { status: resp.status, text: text.slice(Math.max(0, i - 400), Math.min(text.length, i + 800)) };
      }
      return { status: resp.status, text: text.slice(0, maxText) };
    },
  });
  return injected?.result || { error: 'NO_INJECTION_RESULT' };
}

async function handleBatchRpc(msg) {
  const { id, params } = msg;
  const { rpcid, freq, captchaAction, match } = params || {};
  if (!rpcid || !freq) { send({ id, error: 'INVALID_BATCH_RPC' }); return; }
  try {
    const out = await runBatchRpc({ id, rpcid, freq, captchaAction, match });
    if (out.error) {
      if (captchaAction) saveState({ failed: state.failed + 1, lastError: out.error, lastRpc: rpcid });
      send({ id, error: out.error });
    } else {
      if (captchaAction) saveState({ ok: state.ok + 1, lastError: null, lastRpc: rpcid });
      send({ id, status: out.status, data: out.text });
    }
  } catch (e) {
    const err = e?.message || 'BATCH_RPC_FAILED';
    saveState({ failed: state.failed + 1, lastError: err, lastRpc: rpcid });
    send({ id, error: err });
  }
}

// ── điều khiển giao diện tab Flow (CDP qua chrome.debugger) ───────────────────
// App (Playwright) nối tới bridge như tới một Chrome thật; bridge chuyển lệnh CDP tới đây, ta chạy trên tab Flow bằng
// chrome.debugger và gửi kết quả / sự kiện ngược lại. Cách làm giống chế độ extension của Playwright MCP (Apache-2.0).
// Trong lúc app điều khiển, Chrome hiện dải "Flow Batch Studio – Flow Bridge đã bắt đầu gỡ lỗi trình duyệt này": bình thường.

let cdpTabId = null;

async function cdpAttach() {
  const tab = await pickFlowTab();
  if (!tab) throw new Error('NO_FLOW_TAB');
  if (cdpTabId !== null && cdpTabId !== tab.id) await cdpDetach();
  try {
    await chrome.debugger.attach({ tabId: tab.id }, '1.3');
  } catch (e) {
    const m = e?.message || '';
    // service worker vừa khởi động lại nhưng tab vẫn đang gắn từ lần trước: gỡ ra gắn lại cho sạch
    if (!m.includes('already attached')) throw e;
    try { await chrome.debugger.detach({ tabId: tab.id }); } catch {}
    await chrome.debugger.attach({ tabId: tab.id }, '1.3');
  }
  cdpTabId = tab.id;
  addLog('App bắt đầu điều khiển tab Flow', 'info', { attached: true, tabUrl: tab.url });
  const info = await chrome.debugger.sendCommand({ tabId: tab.id }, 'Target.getTargetInfo');
  return { targetInfo: info.targetInfo };
}

async function cdpDetach() {
  const id = cdpTabId;
  cdpTabId = null;
  if (id !== null) { try { await chrome.debugger.detach({ tabId: id }); } catch {} }
  if (state.attached) addLog('App trả lại tab Flow', 'info', { attached: false });
  return {};
}

function handleCdp(msg) {
  const { method, params, sessionId } = msg.params || {};
  if (cdpTabId === null) { send({ id: msg.id, error: 'NOT_ATTACHED' }); return; }
  const target = sessionId ? { tabId: cdpTabId, sessionId } : { tabId: cdpTabId };
  // gọi sendCommand ngay (không await trước đó) để giữ đúng thứ tự các lệnh
  chrome.debugger.sendCommand(target, method, params || {})
    .then((result) => send({ id: msg.id, result: result || {} }))
    .catch((e) => send({ id: msg.id, error: e?.message || String(e) }));
}

chrome.debugger.onEvent.addListener((source, method, params) => {
  if (source.tabId !== cdpTabId) return;
  send({ type: 'cdp_event', method, params, sessionId: source.sessionId || null });
});

chrome.debugger.onDetach.addListener((source, reason) => {
  if (source.tabId !== cdpTabId) return;
  cdpTabId = null;
  addLog(reason === 'canceled_by_user' ? 'Bạn đã bấm Hủy trên dải gỡ lỗi: app dừng điều khiển tab Flow'
         : `Tab Flow ngừng bị điều khiển (${reason})`, 'warn', { attached: false });
  send({ type: 'cdp_detached', reason });
});

// ── tải không hỏi "Lưu" ────────────────────────────────────────────────────
// Chrome bật "Hỏi vị trí lưu từng tệp" (hay gặp trên Windows 10) thì mỗi lần Flow tải file lại hiện hộp Lưu, app chờ mãi
// không thấy file. chrome.downloads.download({saveAs:false}) KHÔNG tắt được hộp này (Chrome vẫn theo cài đặt "Hỏi vị trí
// lưu"), nên bản 0.3.1 vẫn bị hỏi. Từ 0.3.2: bắt lượt tải của trang Flow ngay lúc Chrome xin tên file (TRƯỚC khi hiện hộp
// Lưu), extension tự đọc nội dung file rồi gửi thẳng cho app qua WebSocket; app ghi file vào thư mục Downloads. Lượt tải
// của Chrome bị hủy nên không có hộp Lưu nào. Chưa nối app / không đọc được file thì để Chrome tải như bình thường.
const FLOW_DL = /(^|\.)(flow\.google\.com|labs\.google|flow-content\.google|googleusercontent\.com|storage\.googleapis\.com)(\/|:|$)/;
const XFER_CHUNK = 2 * 1024 * 1024;   // số ký tự base64 mỗi gói gửi app (bội số của 4)

function hostOf(u) {
  try { return new URL(u).hostname; } catch { return ''; }
}

function bytesToBase64(buf) {
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

// Đọc file trong tab Flow (blob: chỉ đọc được trong đúng trang đã tạo nó; https: dùng cookie / CORS của trang) -> data URL
async function readInTab(url) {
  let tab = null;
  if (url.startsWith('blob:')) {
    try {
      const origin = new URL(url.slice(5)).origin;
      tab = (await chrome.tabs.query({ url: origin + '/*' }))[0] || null;
    } catch {}
  }
  if (!tab) tab = await pickFlowTab();
  if (!tab) throw new Error('NO_FLOW_TAB');
  const [res] = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    args: [url],
    func: async (u) => {
      const r = await fetch(u, { credentials: 'include' });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const b = await r.blob();
      return await new Promise((ok, bad) => {
        const fr = new FileReader();
        fr.onload = () => ok(fr.result);
        fr.onerror = () => bad(String(fr.error));
        fr.readAsDataURL(b);
      });
    },
  });
  if (!res?.result) throw new Error('không đọc được file trong tab Flow');
  return res.result;
}

// Nội dung file: { mime, b64 }. https: extension tự tải (có cookie), không được thì đọc trong tab Flow.
async function fetchFileBase64(url) {
  let dataUrl = null;
  if (url.startsWith('data:')) dataUrl = url;
  else if (url.startsWith('blob:')) dataUrl = await readInTab(url);
  else {
    try {
      const r = await fetch(url, { credentials: 'include' });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return { mime: (r.headers.get('content-type') || '').split(';')[0], b64: bytesToBase64(await r.arrayBuffer()) };
    } catch {
      dataUrl = await readInTab(url);
    }
  }
  const comma = dataUrl.indexOf(',');
  const head = dataUrl.slice(5, comma);
  const mime = head.split(';')[0];
  if (/;base64$/i.test(head)) return { mime, b64: dataUrl.slice(comma + 1) };
  return { mime, b64: bytesToBase64(new TextEncoder().encode(decodeURIComponent(dataUrl.slice(comma + 1)))) };
}

// Gửi file cho app theo từng gói; app ghi xong file sẽ coi như một lượt tải bình thường.
function sendFileToApp(name, mime, b64) {
  const xferId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const total = Math.max(1, Math.ceil(b64.length / XFER_CHUNK));
  for (let seq = 0; seq < total; seq++) {
    if (!ws || ws.readyState !== WebSocket.OPEN) throw new Error('mất kết nối app');
    send({ type: 'download_data', xferId, name, mime, seq, total, b64: b64.slice(seq * XFER_CHUNK, (seq + 1) * XFER_CHUNK) });
  }
}

chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  const url = item.finalUrl || item.url || '';
  const fromFlow = FLOW_DL.test(hostOf(item.referrer || '')) || FLOW_DL.test(hostOf(url))
    || (url.startsWith('blob:https://') && FLOW_DL.test(hostOf(url.slice(5))));
  const appOn = ws && ws.readyState === WebSocket.OPEN;
  if (item.byExtensionId || !fromFlow || !appOn || !/^(https?:|blob:|data:)/.test(url)) {
    suggest();
    return;
  }
  const name = (item.filename || '').split(/[\\/]/).pop() || '';
  (async () => {
    let file;
    try {
      file = await fetchFileBase64(url);   // lượt tải của Chrome vẫn đứng chờ tên file trong lúc này: chưa có hộp Lưu
      if (!file.b64) throw new Error('file rỗng');
    } catch (e) {
      addLog(`Không tự đọc được ${name || 'file'} (${e?.message || e}): để Chrome tải như bình thường`, 'warn');
      suggest();
      return;
    }
    try { await chrome.downloads.cancel(item.id); } catch {}
    chrome.downloads.erase({ id: item.id }).catch(() => {});
    try {
      sendFileToApp(name, file.mime || item.mime || '', file.b64);
      addLog(`Đã gửi ${name || 'file'} thẳng cho app (không qua hộp Lưu của Chrome)`, 'info');
    } catch (e) {
      // lượt tải của Chrome đã hủy: tải lại bằng Chrome (có thể bị hỏi vị trí lưu)
      addLog(`Không gửi được ${name || 'file'} cho app (${e?.message || e}): tải lại bằng Chrome`, 'warn');
      chrome.downloads.download({ url: `data:${file.mime || 'application/octet-stream'};base64,${file.b64}`,
                                  filename: name || undefined, conflictAction: 'uniquify' }).catch(() => {});
    }
  })();
  return true;   // trả lời sau (không gọi suggest khi đã tự lấy file)
});

// ── báo app mỗi file tải xong (app chép file từ thư mục Downloads vào phim) ───

chrome.downloads.onChanged.addListener(async (delta) => {
  if (delta.state?.current !== 'complete') return;
  const [item] = await chrome.downloads.search({ id: delta.id });
  if (!item) return;
  send({ type: 'download', filename: item.filename, url: item.finalUrl || item.url, mime: item.mime,
         fileSize: item.fileSize });
  if (state.job) addLog(`Chrome tải xong ${item.filename.split(/[\\/]/).pop()}`, 'info');
});

// ── bảng quản lý cạnh phải (bấm biểu tượng extension để mở) ───────────────────

async function openFlowTab() {
  const tabs = await chrome.tabs.query({ url: FLOW_URLS });
  const tab = tabs.find((t) => t.url && t.url.includes('/project/')) || tabs[0];
  if (tab) {
    await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(tab.windowId, { focused: true });
  } else {
    await chrome.tabs.create({ url: FLOW_TAB_URL, active: true });
  }
}

chrome.sidePanel?.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
