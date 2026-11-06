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
  ws.onopen = () => {
    addLog('Đã kết nối app Flow Batch Studio', 'ok', { connected: true, lastError: null });
    send({ type: 'extension_ready', extensionVersion: chrome.runtime.getManifest().version });
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
  if (a.name === 'reconnect') { connect(); send({ type: 'ping' }); }
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
// Chrome bật "Hỏi vị trí lưu từng tệp" (hay gặp trên Windows 10) thì mỗi lần Flow tải file lại hiện hộp Lưu, phải bấm tay.
// Bắt lượt tải của trang Flow ngay lúc Chrome xin tên file (TRƯỚC khi hiện hộp Lưu), hủy nó, rồi extension tự tải lại
// cùng file với saveAs:false (không bao giờ hỏi). File vẫn về thư mục Downloads như cũ, app nhận như cũ.
const FLOW_DL = /(^|\.)(flow\.google\.com|labs\.google|flow-content\.google|googleusercontent\.com|storage\.googleapis\.com)(\/|:|$)/;

function hostOf(u) {
  try { return new URL(u).hostname; } catch { return ''; }
}

async function blobToDataUrl(blobUrl) {
  // blob: chỉ đọc được trong đúng trang đã tạo nó: tìm tab cùng nguồn (tab Flow)
  let tab = null;
  try {
    const origin = new URL(blobUrl.slice(5)).origin;
    tab = (await chrome.tabs.query({ url: origin + '/*' }))[0] || null;
  } catch {}
  if (!tab) tab = await pickFlowTab();
  if (!tab) throw new Error('NO_FLOW_TAB');
  const [res] = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    args: [blobUrl],
    func: async (u) => {
      const b = await (await fetch(u)).blob();
      return await new Promise((ok, bad) => {
        const r = new FileReader();
        r.onload = () => ok(r.result);
        r.onerror = () => bad(String(r.error));
        r.readAsDataURL(b);
      });
    },
  });
  if (!res?.result) throw new Error('blob rỗng');
  return res.result;
}

chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  const url = item.finalUrl || item.url || '';
  const fromFlow = FLOW_DL.test(hostOf(item.referrer || '')) || FLOW_DL.test(hostOf(url))
    || (url.startsWith('blob:https://') && FLOW_DL.test(hostOf(url.slice(5))));
  if (item.byExtensionId || !fromFlow || !/^(https?:|blob:|data:)/.test(url)) {
    suggest();
    return;
  }
  const name = (item.filename || '').split(/[\/]/).pop() || undefined;
  (async () => {
    try {
      await chrome.downloads.cancel(item.id);
      chrome.downloads.erase({ id: item.id }).catch(() => {});
      const src = url.startsWith('blob:') ? await blobToDataUrl(url) : url;
      await chrome.downloads.download({ url: src, filename: name, saveAs: false, conflictAction: 'uniquify' });
    } catch (e) {
      addLog(`Không tự tải được ${name || 'file'} (${e?.message || e}): bấm Lưu trên hộp thoại của Chrome`, 'warn');
      suggest();   // để Chrome tải như bình thường
    }
  })();
  return true;   // trả lời sau (không gọi suggest khi đã hủy và tải lại)
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
