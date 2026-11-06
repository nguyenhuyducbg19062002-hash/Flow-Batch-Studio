"""
Cầu nối giữa worker Python và extension "Flow Batch Studio – Flow Bridge" trong Chrome.

  extension (Chrome)  --WebSocket-->  ws://127.0.0.1:18321   (extension là phía kết nối tới, tự nối lại mỗi 5 giây)
  worker (driver)     --HTTP POST-->  http://127.0.0.1:18322/rpc   {rpcid, freq, captchaAction?, match?, timeout?}
                                      -> {status, data} hoặc {error}
  Playwright (worker) --CDP WS---->   ws://127.0.0.1:18323         điều khiển giao diện tab Flow (điền prompt, ảnh, bấm Tạo)

Cổng CDP là bộ chuyển tiếp: Playwright connect_over_cdp tới đây như tới một Chrome thật; bridge tự trả lời vài lệnh cấp
trình duyệt (Browser.getVersion, Target.setAutoAttach…) và chuyển các lệnh còn lại cho extension, extension chạy chúng trên
tab Flow bằng chrome.debugger rồi gửi kết quả / sự kiện ngược lại. Mỗi lúc chỉ một Playwright được nối (chỉ có một tab Flow).
Cách làm giống chế độ extension của Playwright MCP (microsoft/playwright-mcp, Apache-2.0).

Extension cũng báo mỗi file tải xong trong Chrome (chrome.downloads): worker đọc ở GET /downloads?since=<epoch giây> rồi
chép file từ thư mục Downloads vào staging.

Chỉ một bridge chạy trên máy: worker nào cần trước thì tự bật bridge trong tiến trình của mình (ensure_bridge);
các worker khác thấy cổng đã có người dùng thì gọi qua HTTP. Worker giữ bridge bị tắt thì worker kế tiếp tự bật lại,
extension tự nối lại sau vài giây.

Bridge cũng là nơi duy nhất giữ nhịp gửi lệnh tạo (lệnh có captcha) cho cả máy: lần lượt từng lệnh, cách nhau tối thiểu
AVS_FLOW_MIN_GAP_S giây; Flow báo "unusual activity" thì ngừng gửi lệnh tạo trong AVS_FLOW_HOLD_S giây.

Ý tưởng và giao thức tham khảo FlowKit (https://github.com/crisng95/flowkit, MIT).
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

log = logging.getLogger("flow-bridge")

HOST = "127.0.0.1"
WS_PORT = int(os.environ.get("AVS_FLOW_WS_PORT", "18321"))
HTTP_PORT = int(os.environ.get("AVS_FLOW_HTTP_PORT", "18322"))
CDP_PORT = int(os.environ.get("AVS_FLOW_CDP_PORT", "18323"))
CDP_URL = f"ws://{HOST}:{CDP_PORT}"
CDP_PAGE_SESSION = "avs-flow-tab"
BRIDGE_URL = f"http://{HOST}:{HTTP_PORT}"
# Header bắt buộc: trang web lạ trong trình duyệt không gửi được header tự đặt tới 127.0.0.1 nếu không qua preflight CORS,
# mà bridge không trả lời preflight, nên chỉ chương trình trên máy (worker) gọi được /rpc.
AUTH_HEADER = "X-AVS-Bridge"

MIN_GAP_S = float(os.environ.get("AVS_FLOW_MIN_GAP_S", "3"))
HOLD_S = float(os.environ.get("AVS_FLOW_HOLD_S", "3600"))
MAX_MESSAGE = 64 * 1024 * 1024


class _State:
    def __init__(self):
        self.loop: asyncio.AbstractEventLoop | None = None
        self.ws = None
        self.ext_info: dict = {}
        self.pending: dict[str, asyncio.Future] = {}
        self.gen_lock: asyncio.Lock | None = None
        self.last_gen = 0.0
        self.hold_until = 0.0
        self.hold_reason = ""
        self.cdp_client = None                    # Playwright đang nối cổng CDP
        self.cdp_pending: dict[str, tuple] = {}   # id lệnh gửi extension -> (id lệnh của Playwright, sessionId)
        self.downloads: list[dict] = []           # file Chrome tải xong gần đây (mới nhất ở cuối)


S = _State()


# ── phía extension (WebSocket) ────────────────────────────────────────────────

async def _ws_handler(ws):
    origin = (ws.request.headers.get("Origin") or "") if getattr(ws, "request", None) else ""
    if origin and not origin.startswith("chrome-extension://"):
        await ws.close(1008, "origin not allowed")
        return
    if S.ws is not None:
        try:
            await S.ws.close(1000, "replaced")
        except Exception:
            pass
    S.ws = ws
    log.info("Extension đã kết nối")
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            t = msg.get("type")
            if t == "ping":
                await ws.send(json.dumps({"type": "pong"}))
            elif t == "extension_ready":
                S.ext_info = {k: v for k, v in msg.items() if k != "type"}
                log.info("Extension sẵn sàng: %s", S.ext_info)
            elif t == "cdp_event":
                await _to_playwright({"method": msg.get("method"), "params": msg.get("params") or {},
                                      "sessionId": msg.get("sessionId") or CDP_PAGE_SESSION})
            elif t == "cdp_detached":
                log.info("Extension thôi điều khiển tab Flow: %s", msg.get("reason"))
                if S.cdp_client is not None:
                    await S.cdp_client.close(1001, "tab detached")
            elif t == "download":
                d = {k: msg.get(k) for k in ("filename", "url", "mime", "fileSize")}
                d["at"] = time.time()
                S.downloads = (S.downloads + [d])[-50:]
                log.info("Chrome tải xong: %s", d.get("filename"))
            elif msg.get("id") in S.cdp_pending:
                pw_id, session = S.cdp_pending.pop(msg["id"])
                reply = {"id": pw_id}
                if session:
                    reply["sessionId"] = session
                if msg.get("error"):
                    reply["error"] = {"code": -32000, "message": str(msg["error"])}
                else:
                    reply["result"] = msg.get("result") or {}
                await _to_playwright(reply)   # trả lời ngay tại đây: giữ đúng thứ tự với các sự kiện
            elif msg.get("id") in S.pending:
                fut = S.pending.pop(msg["id"])
                if not fut.done():
                    fut.set_result(msg)
    finally:
        if S.ws is ws:
            S.ws = None
            S.ext_info = {}
            log.info("Extension đã ngắt kết nối")
            for fut in list(S.pending.values()):
                if not fut.done():
                    fut.set_result({"error": "EXTENSION_DISCONNECTED"})
            S.pending.clear()
            if S.cdp_client is not None:
                await S.cdp_client.close(1001, "extension disconnected")


async def _send(params: dict, timeout: float, method: str = "batch_rpc") -> dict:
    if S.ws is None:
        return {"error": "NO_EXTENSION"}
    rid = str(uuid.uuid4())
    fut = asyncio.get_running_loop().create_future()
    S.pending[rid] = fut
    try:
        await S.ws.send(json.dumps({"id": rid, "method": method, "params": params}))
        return await asyncio.wait_for(fut, timeout)
    except asyncio.TimeoutError:
        return {"error": "EXTENSION_TIMEOUT"}
    except Exception as e:  # ws đóng giữa chừng
        return {"error": f"EXTENSION_SEND_FAILED: {e}"}
    finally:
        S.pending.pop(rid, None)


async def _rpc(body: dict) -> dict:
    params = {k: body[k] for k in ("rpcid", "freq", "captchaAction", "match") if body.get(k)}
    timeout = float(body.get("timeout") or 120)
    if not params.get("rpcid") or not params.get("freq"):
        return {"error": "INVALID_RPC"}
    if not params.get("captchaAction"):
        return await _send(params, timeout)

    # lệnh tạo: lần lượt, giãn nhịp, tôn trọng thời gian nghỉ khi Flow nghi ngờ
    async with S.gen_lock:
        now = time.time()
        if now < S.hold_until:
            return {"error": f"FLOW_HOLD: {S.hold_reason} (còn {int(S.hold_until - now)} giây)"}
        wait = S.last_gen + MIN_GAP_S - now
        if wait > 0:
            await asyncio.sleep(wait)
        out = await _send(params, timeout)
        S.last_gen = time.time()
        text = f"{out.get('error') or ''} {out.get('data') or ''}"[:200000]
        if "PUBLIC_ERROR_UNUSUAL_ACTIVITY" in text:
            S.hold_until = time.time() + HOLD_S
            S.hold_reason = "PUBLIC_ERROR_UNUSUAL_ACTIVITY"
            log.warning("Flow báo unusual activity: ngừng gửi lệnh tạo %s giây", int(HOLD_S))
        return out


def _status() -> dict:
    now = time.time()
    return {"connected": S.ws is not None, "extension": S.ext_info, "cdpBusy": S.cdp_client is not None,
            "holdSeconds": max(0, int(S.hold_until - now)), "holdReason": S.hold_reason if now < S.hold_until else ""}


# ── phía Playwright (CDP qua extension) ──────────────────────────────────────

async def _to_playwright(obj: dict):
    c = S.cdp_client
    if c is None:
        return
    try:
        await c.send(json.dumps(obj))
    except Exception:
        pass


def _browser_reply(method: str):
    """Lệnh cấp trình duyệt bridge tự trả lời. None = chuyển cho extension."""
    if method == "Browser.getVersion":
        return {"protocolVersion": "1.3", "product": "Chrome/AVS-Flow-Bridge", "revision": "",
                "userAgent": "AVS-Flow-Bridge", "jsVersion": ""}
    if method in ("Browser.setDownloadBehavior", "Target.setDiscoverTargets", "Browser.close", "Target.detachFromTarget"):
        return {}
    return None


async def _cdp_handler(client):
    origin = (client.request.headers.get("Origin") or "") if getattr(client, "request", None) else ""
    if origin:   # chỉ chương trình trên máy (Playwright) được nối, trang web thì không
        await client.close(1008, "origin not allowed")
        return
    if S.cdp_client is not None:
        await client.close(1013, "busy")
        return
    if S.ws is None:
        await client.close(1011, "NO_EXTENSION")
        return
    S.cdp_client = client
    target_info: dict = {}
    log.info("Playwright nối vào tab Flow qua extension")
    try:
        async for raw in client:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            pw_id, method, params = msg.get("id"), msg.get("method") or "", msg.get("params") or {}
            session = msg.get("sessionId")
            reply = {"id": pw_id}
            if session:
                reply["sessionId"] = session
            if not session and method == "Target.setAutoAttach":
                out = await _send({}, 30, method="cdp_attach")
                if out.get("error"):
                    reply["error"] = {"code": -32000, "message": f"Không điều khiển được tab Flow: {out['error']}"}
                    await _to_playwright(reply)
                    continue
                target_info = dict((out.get("result") or {}).get("targetInfo") or {}, attached=True)
                await _to_playwright({"method": "Target.attachedToTarget",
                                      "params": {"sessionId": CDP_PAGE_SESSION, "targetInfo": target_info,
                                                 "waitingForDebugger": False}})
                reply["result"] = {}
                await _to_playwright(reply)
                continue
            if method == "Target.getTargetInfo" and (not session or session == CDP_PAGE_SESSION) and target_info:
                reply["result"] = {"targetInfo": target_info}
                await _to_playwright(reply)
                continue
            local = None if session else _browser_reply(method)
            if local is not None:
                reply["result"] = local
                await _to_playwright(reply)
                continue
            if S.ws is None:
                reply["error"] = {"code": -32000, "message": "NO_EXTENSION"}
                await _to_playwright(reply)
                continue
            rid = str(uuid.uuid4())
            S.cdp_pending[rid] = (pw_id, session)
            child = session if session and session != CDP_PAGE_SESSION else None
            try:
                await S.ws.send(json.dumps({"id": rid, "method": "cdp",
                                            "params": {"method": method, "params": params, "sessionId": child}}))
            except Exception as e:
                S.cdp_pending.pop(rid, None)
                reply["error"] = {"code": -32000, "message": f"EXTENSION_SEND_FAILED: {e}"}
                await _to_playwright(reply)
    except Exception as e:   # Playwright rớt giữa chừng
        log.debug("Kết nối CDP đóng: %s", e)
    finally:
        S.cdp_client = None
        S.cdp_pending.clear()
        log.info("Playwright rời tab Flow")
        if S.ws is not None:
            try:
                await _send({}, 10, method="cdp_detach")
            except Exception:
                pass


async def _progress(body: dict):
    """Tiến độ cảnh đang chạy -> bảng quản lý của extension (không chờ trả lời)."""
    if S.ws is None:
        return
    msg = {k: body.get(k) for k in ("event", "jobId", "label", "kind", "mode", "text")}
    msg["type"] = "job"
    try:
        await S.ws.send(json.dumps(msg, ensure_ascii=False))
    except Exception:
        pass


def _downloads_since(since: float) -> list[dict]:
    return [d for d in S.downloads if d["at"] >= since]


# ── phía worker (HTTP) ────────────────────────────────────────────────────────

class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _reply(self, code: int, obj: dict):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self) -> bool:
        if self.headers.get(AUTH_HEADER) != "1" or self.headers.get("Origin"):
            self._reply(403, {"error": "forbidden"})
            return False
        return True

    def do_GET(self):
        if not self._authorized():
            return
        if self.path == "/status":
            self._reply(200, asyncio.run_coroutine_threadsafe(_async(_status), S.loop).result(5))
        elif self.path.startswith("/downloads"):
            from urllib.parse import parse_qs, urlparse
            try:
                since = float((parse_qs(urlparse(self.path).query).get("since") or ["0"])[0])
            except ValueError:
                since = 0.0
            self._reply(200, {"downloads": _downloads_since(since)})
        else:
            self._reply(404, {"error": "not found"})

    def do_POST(self):
        if not self._authorized():
            return
        if self.path not in ("/rpc", "/progress"):
            self._reply(404, {"error": "not found"})
            return
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        except ValueError:
            self._reply(400, {"error": "bad json"})
            return
        if self.path == "/progress":
            asyncio.run_coroutine_threadsafe(_progress(body), S.loop)
            self._reply(200, {"ok": True})
            return
        timeout = float(body.get("timeout") or 120)
        try:
            # chờ thêm thời gian xếp hàng sau các lệnh tạo khác
            out = asyncio.run_coroutine_threadsafe(_rpc(body), S.loop).result(timeout + 900)
        except Exception as e:
            out = {"error": f"BRIDGE_ERROR: {e}"}
        self._reply(200, out)


async def _async(fn):
    return fn()


# ── khởi động ────────────────────────────────────────────────────────────────

def _port_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind((HOST, port))
            return True
        except OSError:
            return False


def _run(ready: threading.Event, failed: list):
    import websockets

    async def main():
        S.loop = asyncio.get_running_loop()
        S.gen_lock = asyncio.Lock()
        try:
            server = await websockets.serve(_ws_handler, HOST, WS_PORT, max_size=MAX_MESSAGE, ping_interval=20)
        except OSError as e:
            failed.append(e)
            ready.set()
            return
        try:
            cdp_server = await websockets.serve(_cdp_handler, HOST, CDP_PORT, max_size=MAX_MESSAGE, ping_interval=None)
        except OSError as e:
            server.close()
            failed.append(e)
            ready.set()
            return
        try:
            httpd = ThreadingHTTPServer((HOST, HTTP_PORT), _Handler)
        except OSError as e:
            server.close()
            cdp_server.close()
            failed.append(e)
            ready.set()
            return
        threading.Thread(target=httpd.serve_forever, name="flow-bridge-http", daemon=True).start()
        log.info("Flow bridge chạy: ws://%s:%s (extension), %s (worker), %s (CDP)", HOST, WS_PORT, BRIDGE_URL, CDP_URL)
        ready.set()
        await asyncio.Future()

    asyncio.run(main())


_start_lock = threading.Lock()
_started = False


def bridge_alive() -> bool:
    try:
        return requests.get(f"{BRIDGE_URL}/status", headers={AUTH_HEADER: "1"}, timeout=3).ok
    except requests.RequestException:
        return False


def ensure_bridge() -> None:
    """Có bridge đang chạy (ở tiến trình này hoặc tiến trình khác) thì thôi; chưa có thì bật trong tiến trình này."""
    global _started
    if bridge_alive():
        return
    with _start_lock:
        if _started or bridge_alive():
            return
        if not (_port_free(WS_PORT) and _port_free(HTTP_PORT) and _port_free(CDP_PORT)):
            # worker khác vừa giành được cổng: chờ nó mở xong
            for _ in range(10):
                time.sleep(0.5)
                if bridge_alive():
                    return
            raise RuntimeError(f"Cổng {WS_PORT}/{HTTP_PORT}/{CDP_PORT} đang bị chương trình khác dùng")
        ready, failed = threading.Event(), []
        threading.Thread(target=_run, args=(ready, failed), name="flow-bridge", daemon=True).start()
        ready.wait(10)
        if failed:
            if bridge_alive():
                return
            raise RuntimeError(f"Không mở được Flow bridge: {failed[0]}")
        _started = True


def status() -> dict:
    ensure_bridge()
    return requests.get(f"{BRIDGE_URL}/status", headers={AUTH_HEADER: "1"}, timeout=5).json()


def rpc(rpcid: str, freq: str, captcha_action: str | None = None, match: str | None = None,
        timeout: float = 120) -> dict:
    """Gửi một lệnh batchexecute cho extension. Trả {status, data} hoặc {error}."""
    ensure_bridge()
    body = {"rpcid": rpcid, "freq": freq, "captchaAction": captcha_action, "match": match, "timeout": timeout}
    r = requests.post(f"{BRIDGE_URL}/rpc", json=body, headers={AUTH_HEADER: "1"}, timeout=timeout + 960)
    r.raise_for_status()
    return r.json()


def progress(event: str, job: dict | None = None, text: str = "") -> None:
    """Báo bảng quản lý của extension: event = start | step | done | fail. Không bao giờ ném lỗi."""
    job = job or {}
    kind = job.get("kind")
    label = f"{'Video' if kind == 'VIDEO' else 'Ảnh'} {job.get('externalId') or ''}".strip() if job else ""
    body = {"event": event, "jobId": job.get("jobId"), "label": label, "kind": kind, "mode": job.get("runMode"), "text": text}
    try:
        requests.post(f"{BRIDGE_URL}/progress", json=body, headers={AUTH_HEADER: "1"}, timeout=3)
    except Exception:
        pass


def downloads(since: float) -> list[dict]:
    """Các file Chrome tải xong từ thời điểm since (epoch giây): [{filename, url, mime, fileSize, at}]."""
    ensure_bridge()
    r = requests.get(f"{BRIDGE_URL}/downloads", params={"since": since}, headers={AUTH_HEADER: "1"}, timeout=10)
    r.raise_for_status()
    return r.json().get("downloads") or []


if __name__ == "__main__":  # chạy riêng để thử: python -m flow_ext.bridge
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    ensure_bridge()
    while True:
        time.sleep(3600)
