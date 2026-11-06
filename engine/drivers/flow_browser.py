"""
Google Flow qua Playwright + Google Chrome (không có Chrome thì Edge; profile riêng của từng tài khoản, xem stealth.launch_profile).

Flow 11/2026 (đã chạy thật): trong một dự án, ô prompt là ô chat của "Tác nhân". Ảnh reference là "thành phần" của
dự án (giữ nguyên tên file, ví dụ CHAR-CALEB-1.png); gõ "@" + ID rồi bấm đúng tên file → chèn chip mention.
Tác nhân đôi khi lập kế hoạch rồi hỏi "Would you like me to proceed…?": driver tự trả lời đồng ý (agent_auto_reply).

BACKGROUND: worker tự upload ảnh còn thiếu, gõ prompt + chip @tag, bấm tạo, chờ ô kết quả mới, tải về staging.
FOREGROUND: mở trình duyệt có giao diện, tự upload ảnh + gõ prompt có chip @tag (không bấm tạo); người dùng kiểm tra,
            bấm Tạo rồi Tải xuống.
            Khi người dùng bấm Download trên Flow, hook context.on("download") lưu file vào staging với tên = ID.

Selector của Flow nằm trong engine/selectors.json, đọc lại mỗi job: Flow đổi giao diện thì sửa file đó, không cần build lại.
"""
import atexit
import json
import logging
import os
import random
import re
import time
import unicodedata
from collections import Counter
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import unquote, urlparse

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import TimeoutError as PlaywrightTimeout
from playwright.sync_api import sync_playwright

from .errors import TabLost
from .errors import (AccountBroken, AuthRequired, BotSuspected, Cancelled, ConfigError, GenerationResult, PausedByUser, QuotaExceeded,
                     Rejected, TransientError, proxy_error)
from .flow_session import SessionRunner, Tile
from .helper_overlay import open_in_explorer, overlay_script, prepare_refs_folder
from .stealth import browser_name, detect_and_solve_turnstile, human_click, human_type, launch_profile, send_prompt
from . import pace as pace_mod

FLOW_URL = "https://flow.google.com/"
VIDEO_QUALITIES = ["1080p", "720p", "480p", "360p"]   # từ cao xuống thấp
DEFAULT_VIDEO_QUALITY = "720p"   # phim không đặt chất lượng thì dùng 720p; người dùng đổi ở phần Phim
log = logging.getLogger("flow")

# Khóa bắt buộc phải cấu hình cho chế độ BACKGROUND (FOREGROUND chỉ cố gắng, thiếu thì người dùng tự làm tay)
BACKGROUND_KEYS = ["prompt_box", "add_reference_button", "upload_button", "ingredient_listbox", "generate_button",
                   "result_card", "tile_menu_button", "download_menu_item"]


def parse_proxy(p: str | None):
    if not p or not p.strip():
        return None
    p = p.strip()
    parts = p.split(":")
    if len(parts) == 4 and "://" not in p:  # ip:port:user:pass
        ip, port, user, pwd = parts
        return {"server": f"http://{ip}:{port}", "username": user, "password": pwd}
    u = urlparse(p if "://" in p else f"http://{p}")
    try:
        port = u.port
    except ValueError:
        port = None
    if not u.hostname:
        return {"server": p if "://" in p else f"http://{p}"}
    cfg = {"server": f"{u.scheme}://{u.hostname}" + (f":{port}" if port else "")}
    if u.username:
        cfg["username"] = unquote(u.username)
        cfg["password"] = unquote(u.password or "")
    return cfg


def proxy_label(p: str | None) -> str:
    """Chỉ host:port của proxy để ghi log / báo lỗi (không bao giờ có user:pass)."""
    cfg = parse_proxy(p)
    return cfg["server"].split("://", 1)[-1] if cfg else ""


_PROJECT_RE = re.compile(r"^(https://flow\.google\.com/project/[0-9a-fA-F-]{36})")
_EDIT_RE = re.compile(r"/project/[0-9a-fA-F-]{36}/edit/([0-9a-fA-F-]{36})")


def project_url(url: str | None) -> str:
    """https://flow.google.com/project/<uuid>/edit/<media>?x=1 -> https://flow.google.com/project/<uuid>"""
    m = _PROJECT_RE.match(url or "")
    return m.group(1) if m else (url or "").split("?")[0]


def edit_media_id(url: str | None) -> str | None:
    """Flow tự mở màn chỉnh sửa video vừa tạo: /project/<uuid>/edit/<media id>"""
    m = _EDIT_RE.search(url or "")
    return m.group(1) if m else None


def _status(page, text: str):
    """Dòng trạng thái trên hộp hướng dẫn (FOREGROUND): người dùng biết app đang làm gì, đừng đóng cửa sổ giữa chừng."""
    try:
        page.evaluate("t => { window.__afmLastStatus = t; if (window.__afmStatus) window.__afmStatus(t); }", text)
    except PlaywrightError:
        pass


class StepTimer:
    """Đo thời gian từng bước của một job để biết chỗ nào chậm. Cuối job ghi một dòng log: `[thời gian] tên=giây …`."""

    def __init__(self):
        self.t0 = time.monotonic()
        self.steps: list[tuple[str, float]] = []

    @contextmanager
    def step(self, name: str):
        t = time.monotonic()
        try:
            yield
        finally:
            self.steps.append((name, time.monotonic() - t))

    def summary(self) -> str:
        parts = " ".join(f"{n}={d:.1f}s" for n, d in self.steps)
        return f"[{time.monotonic() - self.t0:.1f}s] {parts}"


def _is_todo(v) -> bool:
    return v is None or (isinstance(v, str) and (not v.strip() or v.startswith("TODO")))


# (dự án Flow, loại, model, tỉ lệ, thinking, chất lượng, độ dài) đã được cài môi trường đầy đủ trong tiến trình worker này
_ENV_READY: set = set()

# Nút chip cạnh ô prompt mở bảng cài đặt ("🍌 Nano Banana 2.1 ▭ x2"; xác nhận 2026-11-06 qua DevTools: <button> chứa
# <mat-icon>crop_16_9</mat-icon>). KHÔNG dùng nút ⚙ trên thanh trên cùng (đó là cài đặt dự án).
SETTINGS_CHIP = "button:has(mat-icon:text-matches('^crop_'))"


def chip_state(text: str) -> dict:
    """Đọc chữ trên chip: model, tỉ lệ (crop_16_9 → 16:9), số bản (x2)."""
    t = " ".join((text or "").split())
    m_ratio = re.search(r"crop_(\d+)_(\d+)", t)
    m_count = re.search(r"(?:^|\s)x(\d)(?:\s|$)", t)
    model = re.sub(r"crop_\w+|(?:^|\s)x\d(?=\s|$)|arrow_drop_down|[^\w\s.:-]", " ", t)
    return {"ratio": f"{m_ratio.group(1)}:{m_ratio.group(2)}" if m_ratio else None,
            "count": int(m_count.group(1)) if m_count else None,
            "model": " ".join(model.split()), "text": t}

# Thinking level trong bảng cài đặt ảnh (giao diện có thể là tiếng Anh hoặc tiếng Việt)
THINKING_LEVELS = {
    "low": r"^\s*(Low|Thấp)\s*$",
    "medium": r"^\s*(Medium|Trung bình)\s*$",
    "high": r"^\s*(High|Cao)\s*$",
}


class _SessionOps:
    """FlowOps thật cho SessionRunner (docs/SONG-SONG.md): đọc lưới ô, gửi lệnh, tải kết quả, đọc thông báo lỗi của trang."""

    # Ô do Flow TẠO RA, mới nhất ở đầu (đã kiểm chứng trên Flow thật: thứ tự ổn định, dấu gắn lên ô thì không bền).
    # Bỏ ô ảnh người dùng tải lên: nhãn là tên file VÀ là ảnh (không phải video) đã tải xong VÀ không đang chạy. Ô đang xếp hàng /
    # đang tạo có nhãn là prompt, mà prompt có thể kết thúc bằng một @tag ảnh ("…CHAR-X-1.png"): không được loại nhầm.
    _TILES_JS = """([card]) => { /*KEYS*/
        return [...document.querySelectorAll(card)].map(c => {
            const label = (c.getAttribute('aria-label') || '').trim();
            const txt = (c.innerText || '').replace(/\\s+/g, ' ');
            const m = txt.match(/(\\d+) *%/);
            const busy = !!m || !!c.querySelector('[role=progressbar], mat-progress-spinner, mat-spinner');
            const img = c.querySelector('img');
            const imgOk = !!(img && img.complete && img.naturalWidth > 0);
            const isVideo = !!c.querySelector('video, flow-video-tile');
            const uploaded = !busy && !isVideo && imgOk && /[.](png|jpe?g|webp|gif|bmp)$/i.test(label);
            // ô lỗi của Flow: chữ "Failed" ở đầu + lý do, không có % / vòng quay (đã thấy: "We noticed some unusual activity…")
            const failed = !busy && /^\\s*(\\S{0,12}\\s*)?(failed\\b|không thành công)/i.test(c.innerText || '');
            return {c, label, m, busy, imgOk, uploaded, failed, err: failed ? (c.innerText || '').replace(/\\s+/g, ' ').trim() : ''};
        }).filter(x => !x.uploaded).map(x => {
            const k = keyOf(x.c); if (k) x.c.setAttribute('data-afm-key', k);
            return {key: k, label: x.label.slice(0, 120), pct: x.m ? +x.m[1] : null, busy: x.busy, imgOk: x.imgOk,
                    failed: x.failed, err: x.err.slice(0, 300)};
        }); }"""

    def __init__(self, driver: "FlowBrowserDriver", page, s: dict, ctx=None):
        self.d, self.page, self.s, self.ctx = driver, page, s, ctx

    def media_url_in_new_tab(self, key: str) -> str | None:
        """
        Địa chỉ màn chỉnh sửa (…/edit/<media id>) của một ô đã xong. Đọc nó phải mở ô rồi quay lại lưới = TẢI LẠI lưới, mà sau khi
        tải lại Flow sắp xếp ô khác thứ tự lúc đang chạy (đã quan sát: ô video chen giữa các ô ảnh) → làm hỏng việc gắn kết quả theo
        vị trí của các lệnh còn đang chạy. Nên làm trên một TAB PHỤ (tìm ô theo khoá, khoá ổn định giữa các lần tải đã kiểm chứng) và
        không bao giờ tải lại trang chính.
        """
        if self.ctx is None:
            return None
        tab = None
        try:
            tab = self.ctx.new_page()
            tab.set_default_timeout(30_000)
            tab.goto(project_url(self.page.url), wait_until="domcontentloaded")
            tab.locator(self.s["result_card"]).first.wait_for(state="attached", timeout=20_000)
            tab.wait_for_timeout(1_500)
            self.d._dismiss_popups(tab)
            return self.d._media_url(tab, self.s, key)
        except (PlaywrightError, PlaywrightTimeout) as e:
            log.warning("Không lấy được địa chỉ chỉnh sửa của %s: %s", key, str(e)[:160])
            return None
        finally:
            if tab is not None:
                try:
                    tab.close()
                except PlaywrightError:
                    pass
            try:
                self.page.bring_to_front()
            except PlaywrightError:
                pass

    # Lưới Flow dùng cuộn ảo (.cdk-virtual-scrollable): chỉ ~16 ô nằm trong trang và cửa sổ đó theo vị trí cuộn. Muốn thấy các ô
    # MỚI NHẤT (để gắn kết quả theo vị trí từ trên cùng) thì phải đưa lưới về đầu; bước tải về cuộn ô vào tầm nhìn nên sau đó
    # lần đọc kế tiếp phải cuộn lại lên đầu.
    _SCROLL_TOP_JS = """() => { const el = document.querySelector('.cdk-virtual-scrollable');
        if (el && el.scrollTop > 2) { el.scrollTop = 0; return true; } return false; }"""

    def generated_tiles(self) -> list[Tile]:
        if self.page.evaluate(self._SCROLL_TOP_JS):
            self.page.wait_for_timeout(400)   # cuộn ảo vẽ lại cửa sổ ô ở đầu lưới
        js = self._TILES_JS.replace("/*KEYS*/", FlowBrowserDriver._TILE_KEYS_JS)   # ghép lúc gọi: lớp này khai báo trước FlowBrowserDriver
        rows = self.page.evaluate(js, [self.s["result_card"]])
        return [Tile(key=r["key"], label=r["label"], pct=r["pct"],
                     ready=bool(r["key"]) and not r["busy"] and r["imgOk"] and not r.get("failed"),
                     failed=bool(r.get("failed")), error=r.get("err") or "") for r in rows]

    @staticmethod
    def _norm(text: str) -> str:
        return re.sub(r"\s+", " ", text or "").strip().lower()

    def _prompt_box_empty(self) -> bool:
        try:
            return not self.page.locator(self.s["prompt_box"]).first.inner_text(timeout=1_500).strip()
        except PlaywrightError:
            return False

    def _accepted(self, tiles: list[Tile], before_len: int, before_top, before_labels: set, prompt: str) -> bool:
        """
        Flow đã nhận lệnh vừa bấm Tạo chưa? KHÔNG được chỉ dựa vào tổng số ô (cuộn ảo: có thể không đổi dù đã nhận → gửi trùng,
        tốn credit). Chấp nhận khi: số ô tăng, HOẶC ô mới ở trên cùng mang nhãn = đầu prompt (ô đang chạy lấy prompt làm nhãn),
        HOẶC ô trên cùng đang chạy và đổi so với trước, ô prompt đã được xóa trống (dùng khi prompt có @tag làm nhãn khác chữ).
        """
        if not tiles:
            return False
        if len(tiles) > before_len:
            return True
        head = self._norm(prompt)[:30]
        if head and any(self._norm(t.label).startswith(head) and t.label not in before_labels for t in tiles[:3]):
            return True
        top = tiles[0]
        changed = before_top is None or (top.key, top.label) != (before_top.key, before_top.label)
        return top.key is None and changed and self._prompt_box_empty()

    def submit(self, job: dict) -> None:
        d, page, s = self.d, self.page, self.s
        d._pace = pace_mod.Pace(pace_mod.factor(job))
        d._dismiss_popups(page)
        d._pace.pause(page, 1.5, 4.0)   # như người chuyển sang mục mới
        env_key = d._setup_env_once(page, s, job)
        d._select_mode(page, s, job["kind"])
        uploaded = d._upload_refs(page, s, job["referenceImages"])
        page.wait_for_timeout(1_500 if uploaded else 150)   # ô ảnh vừa tải lên hiện trong lưới nhanh hơn
        before = self.generated_tiles()
        before_top = before[0] if before else None
        before_labels = {t.label for t in before[:5]}
        attached = d._compose_prompt(page, s, job["prompt"], d._ref_ids(job), mention_all=job["kind"] == "IMAGE") or set()
        need = d._needed_refs(d._shown_prompt(job), d._ref_ids(job))
        if need - attached:
            raise TransientError(f"Chỉ gắn được {len(need & attached)}/{len(need)} ảnh @tag vào prompt: không bấm Tạo")
        d._check_env_before_send(page, s, job, env_key)
        d._pace.pause(page, 1.0, 2.5)   # đọc lại prompt rồi mới bấm Tạo
        reply_sel, auto_reply = s.get("agent_reply_marker"), s.get("agent_auto_reply")
        can_reply = not _is_todo(reply_sel) and bool(auto_reply)
        replies = page.locator(reply_sel).count() if can_reply else 0
        send_prompt(page, s)
        deadline = time.monotonic() + 20
        replied, idle_since = False, None
        while time.monotonic() < deadline:
            page.wait_for_timeout(300)
            if self._accepted(self.generated_tiles(), len(before), before_top, before_labels, job["prompt"]):
                return
            # Tác nhân của Flow trả lời bằng một câu hỏi xác nhận (chưa tạo gì) và đứng chờ: trả lời đồng ý MỘT lần rồi chờ tiếp,
            # thay vì bỏ cuộc (lệnh quay về hàng đợi -> gửi lại -> Flow hỏi lại, lặp mãi)
            if can_reply and not replied and page.locator(reply_sel).count() > replies:
                idle_since = idle_since or time.monotonic()
                if time.monotonic() - idle_since > 4:
                    log.info("Tác nhân Flow hỏi lại: tự trả lời \"%s\"", auto_reply)
                    d._send(page, s, auto_reply)
                    replied = True
                    deadline = time.monotonic() + 40
            err = self.page_error()
            if err:
                if err[0] == "suspicious":
                    raise BotSuspected(f"Flow nghi ngờ hoạt động tự động: {err[1]}")
                raise (QuotaExceeded if err[0] == "quota" else Rejected)(f"Flow báo: {err[1]}")
        raise TransientError("Flow không nhận lệnh: không thấy ô mới ở đầu lưới sau 20s")

    def download(self, job: dict, tile: Tile) -> GenerationResult:
        d, page, s = self.d, self.page, self.s
        d._dismiss_popups(page)
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        # Ô đã tạo xong trên Flow: lỗi khi tải về (nút bị che, menu chưa mở…) KHÔNG được làm lệnh quay về hàng đợi (sẽ tạo lại
        # ảnh đã có, tốn lượt) -> thử lại vài lần, mỗi lần đóng hộp thoại / menu đang che trước.
        last: Exception | None = None
        path = None
        for attempt in range(3):
            try:
                path = d._download(page, s, tile.key, staging, job["externalId"], job["kind"],
                                   job.get("videoQuality") or DEFAULT_VIDEO_QUALITY)
                break
            except (PlaywrightError, PlaywrightTimeout) as e:
                last = e
                log.warning("Tải kết quả job %s lần %d/3 lỗi, thử lại: %s", job.get("jobId"), attempt + 1, str(e)[:160])
                try:
                    page.keyboard.press("Escape")
                except PlaywrightError:
                    pass
                page.wait_for_timeout(2_000)
                d._dismiss_popups(page)
        if path is None:
            raise TransientError(f"Ô kết quả đã tạo xong nhưng không tải về được: {str(last)[:300]}")
        # địa chỉ màn chỉnh sửa (cho "Sửa trên Flow") không đọc được từ ô: phải mở ô; làm ở tab phụ để KHÔNG tải lại trang chính
        media_url = self.media_url_in_new_tab(tile.key) if job["kind"] == "VIDEO" else None
        return GenerationResult(staged_file=path, remote_ref=project_url(page.url), media_url=media_url)

    def page_error(self):
        for kind, key in (("suspicious", "suspicious_texts"), ("quota", "quota_texts"), ("policy", "policy_texts")):
            texts = [t for t in (self.s.get(key) or []) if not _is_todo(t)]
            if not texts:
                continue
            # một lần đọc cho cả danh sách (get_by_text từng câu mất cả giây mỗi lần đọc)
            hit = self.page.evaluate("""(texts) => { const t = (document.body.innerText || '').toLowerCase();
                return texts.find(x => t.includes(x.toLowerCase())) || null; }""", texts)
            if hit:
                return kind, hit
        return None

    def ensure_grid(self) -> None:
        """Flow tự mở màn chỉnh sửa khi một video xong: quay lại lưới (các ô đang tạo vẫn nằm trên máy chủ Flow)."""
        if not edit_media_id(self.page.url):
            return
        # CẢNH BÁO: tải lại lưới làm Flow sắp xếp ô khác lúc đang chạy; phiên chỉ còn kiểm tra mỏ neo để phát hiện lệch pha
        log.warning("Flow mở màn chỉnh sửa (%s): quay lại lưới dự án (có thể đổi thứ tự ô)", edit_media_id(self.page.url))
        self.page.goto(project_url(self.page.url), wait_until="domcontentloaded")
        try:
            self.page.locator(self.s["result_card"]).first.wait_for(state="attached", timeout=15_000)
        except PlaywrightTimeout:
            pass


class FlowBrowserDriver:
    def __init__(self, selectors_file: Path):
        self.selectors_file = selectors_file
        self._active_browser = None
        atexit.register(self._close_active_browser)

    def _close_active_browser(self):
        ab = getattr(self, "_active_browser", None)
        self._active_browser = None
        if ab:
            try:
                ab["ctx"].close()
            except Exception:
                pass
            try:
                ab["pw_cm"].__exit__(None, None, None)
            except Exception:
                pass

    def _sel(self, required: list[str]) -> dict:
        try:
            s = json.loads(self.selectors_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            raise ConfigError(f"Không đọc được {self.selectors_file}: {e}") from e
        missing = [k for k in required if _is_todo(s.get(k))]
        if missing:
            raise ConfigError(f"selectors.json chưa cấu hình cho chế độ chạy tự động: {', '.join(missing)}. "
                              "Ghi lại bằng: python -m playwright codegen --channel chrome " + FLOW_URL)
        return s

    @contextmanager
    def _browser(self, account: dict, headless: bool, keep_alive: bool = True):
        if not account.get("profileDir"):
            raise ConfigError(f"Tài khoản {account.get('email')} chưa có thư mục profile")
        cfg = parse_proxy(account.get("proxy"))
        if cfg and cfg["server"].startswith("socks5") and cfg.get("username"):
            raise AccountBroken(f"Proxy \"{proxy_label(account.get('proxy'))}\": Chromium không hỗ trợ socks5 có user/pass. "
                                "Đổi sang proxy http hoặc socks5 không mật khẩu")

        # Tái sử dụng trình duyệt nếu cùng tài khoản, cùng chế độ và cửa sổ vẫn đang mở
        ab = getattr(self, "_active_browser", None)
        reused = False
        if ab:
            same_account = ab.get("email") == account.get("email") and ab.get("profileDir") == account.get("profileDir")
            same_headless = ab.get("headless") == headless
            if same_account and same_headless:
                try:
                    ctx = ab.get("ctx")
                    if ctx and ctx.pages and not ctx.pages[0].is_closed():
                        reused = True
                except Exception:
                    reused = False
            if not reused:
                self._close_active_browser()

        if not reused:
            pw_cm = sync_playwright()
            pw = pw_cm.__enter__()
            try:
                ctx = launch_profile(
                    pw, account["profileDir"],
                    headless=headless,
                    proxy=parse_proxy(account.get("proxy")),
                    extra_args=["--start-maximized"],
                    accept_downloads=True,
                    viewport=None,
                )
                self._active_browser = {
                    "email": account.get("email"),
                    "profileDir": account.get("profileDir"),
                    "headless": headless,
                    "pw_cm": pw_cm,
                    "ctx": ctx,
                }
            except PlaywrightError as e:
                try:
                    pw_cm.__exit__(None, None, None)
                except Exception:
                    pass
                msg = str(e)
                if "ProcessSingleton" in msg or "user data directory is already in use" in msg.lower():
                    # thường là cửa sổ đăng nhập của tài khoản còn mở: không tính lượt thử, đưa tài khoản về "Cần đăng nhập"
                    raise AuthRequired(f"Profile của {account['email']} đang mở ở một cửa sổ {browser_name()} khác (cửa sổ đăng nhập?). "
                                       "Đóng cửa sổ đó rồi bấm \"Đã đăng nhập xong\"") from e
                if proxy_error(msg):
                    raise AccountBroken(f"Proxy \"{proxy_label(account.get('proxy'))}\" không dùng được: {proxy_error(msg)}") from e
                raise TransientError(f"Không mở được {browser_name()}: {msg[:500]}") from e
        else:
            ctx = self._active_browser["ctx"]

        try:
            yield ctx
        finally:
            if not keep_alive:
                self._close_active_browser()
            else:
                try:
                    if not ctx.pages or ctx.pages[0].is_closed():
                        self._close_active_browser()
                except Exception:
                    self._close_active_browser()

    def _open_flow(self, ctx, job: dict):
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        page.set_default_timeout(45_000)
        target = project_url(job.get("flowProjectUrl")) or FLOW_URL
        try:
            # Nếu trình duyệt đang sẵn ở dự án rồi thì không cần tải lại trang
            if target and target in page.url and "/project/" in page.url:
                log.info("Trình duyệt đang sẵn ở dự án Flow: %s (không tải lại trang)", page.url)
            else:
                page.goto(target, wait_until="domcontentloaded")
        except PlaywrightTimeout:
            pass
        except PlaywrightError as e:
            code = proxy_error(str(e))
            if code:
                # proxy của tài khoản sai / chết: thử lại trên tài khoản này vô ích → backend tắt tài khoản, xoay tài khoản khác
                raise AccountBroken(f"Proxy \"{proxy_label(job['account'].get('proxy'))}\" không kết nối được ({code}). "
                                    "Sửa hoặc xóa proxy của tài khoản") from e
            raise TransientError(f"Không mở được trang Flow: {str(e)[:300]}") from e
        if "accounts.google.com" in page.url:
            raise AuthRequired(f"{job['account']['email']}: chưa đăng nhập hoặc phiên đã hết hạn")
        detect_and_solve_turnstile(page, timeout_s=3.0)
        return page

    # ---------- các bước trên trang dự án Flow ----------
    @staticmethod
    def _select_mode(page, s: dict, kind: str):
        sel = (s.get("mode_buttons") or {}).get(kind)
        if not _is_todo(sel):
            page.locator(sel).first.click()

    @staticmethod
    def _select_duration(page, s: dict, job: dict):
        """Chọn độ dài video trên Flow (cài đặt phim). Selector "duration_option" có chỗ {seconds}."""
        seconds = job.get("durationSeconds")
        if job["kind"] != "VIDEO" or not seconds:
            return
        option = s.get("duration_option")
        if _is_todo(option):
            log.warning("Chưa cấu hình duration_option trong selectors.json: giữ độ dài mặc định của Flow")
            return
        if not _is_todo(s.get("duration_menu")):
            page.locator(s["duration_menu"]).first.click()
        page.locator(option.replace("{seconds}", str(int(seconds)))).first.click()

    @staticmethod
    def _chip_matches(btn, job: dict) -> bool:
        """
        Flow nhớ cài đặt theo tài khoản và hiện tóm tắt trên chip ("Video · 720p · 8s ▭ x1"). Chip đã có đủ chế độ video,
        độ phân giải, độ dài (nếu phim đặt) và x1 thì khỏi mở popover. Không đọc được chip hoặc thiếu mục nào → đặt như thường.
        """
        try:
            text = btn.last.inner_text(timeout=2_000).lower()
        except PlaywrightError:
            return False
        tokens = set(re.split(r"[\s·]+", text))
        need = ["video", str(job.get("videoQuality") or DEFAULT_VIDEO_QUALITY).lower(), "x1"]
        if job.get("durationSeconds"):
            need.append(f"{int(job['durationSeconds'])}s")
        # chip có ghi tỉ lệ ("16:9") thì tỉ lệ cũng phải đúng; chip chỉ hiện biểu tượng tỉ lệ thì không xét được, bỏ qua
        if job.get("aspectRatio") and any(re.fullmatch(r"\d+:\d+", t) for t in tokens):
            need.append(str(job["aspectRatio"]).strip().lower())
        model = (job.get("flowModel") or "").strip().lower()
        return all(n in tokens for n in need) and (not model or model in text)

    # Lựa chọn trong bảng cài đặt đang được chọn chưa (Flow / Angular Material đánh dấu bằng aria-* hoặc class).
    _IS_ON_JS = """(el) => {
        const on = (e) => !!e && (['aria-checked', 'aria-selected', 'aria-pressed'].some(a => e.getAttribute(a) === 'true')
            || ['on', 'checked', 'active', 'selected'].includes((e.getAttribute('data-state') || '').toLowerCase())
            || /(^|[\\s_-])(selected|active|checked)([\\s_-]|$)/i.test(e.className && e.className.baseVal !== undefined ? e.className.baseVal : (e.className || '')));
        return on(el) || on(el.closest('[role=radio],[role=tab],[role=option],button'));
    }"""

    @staticmethod
    def _is_on(loc) -> bool:
        try:
            return bool(loc.evaluate(FlowBrowserDriver._IS_ON_JS))
        except Exception:   # không đọc được trạng thái: coi như chưa chọn, bấm cho chắc
            return False

    # Tên các dòng model của Flow, để nhận ra nút mở danh sách model trong bảng cài đặt ("Nano Banana 2.1 ▾", "Veo 3.1 - Fast ▾").
    _MODEL_FAMILY = r"(nano\s*banana|imagen|veo|gemini)"

    @staticmethod
    def _pick_model(page, model: str, p, chosen: list) -> None:
        """
        Chọn model trong bảng cài đặt (ô thả xuống dưới tỉ lệ khung). Đang đúng model thì bỏ qua. Không tìm thấy thì giữ model
        hiện tại và ghi log (không chặn việc tạo).
        """
        want = re.compile(re.escape(model), re.I)
        fam = re.compile(FlowBrowserDriver._MODEL_FAMILY, re.I)
        overlay = page.locator(".cdk-overlay-container, [role=dialog], [role=menu]")
        trigger = None
        for loc in (overlay.get_by_role("combobox"), overlay.locator("button[aria-haspopup]"), overlay.get_by_role("button")):
            try:
                cand = loc.filter(has_text=fam)
                if cand.count():
                    trigger = cand.first
                    break
            except PlaywrightError:
                continue
        if trigger is None:
            log.warning("Không thấy ô chọn model trong bảng cài đặt Flow: giữ model hiện tại")
            return
        try:
            if want.search(trigger.inner_text(timeout=2_000) or ""):
                chosen.append(f"model {model} (đã đúng)")
                return
            p.pause(page, 0.4, 0.9)
            trigger.click(timeout=5_000)
            page.wait_for_timeout(300)
            p.pause(page, 0.3, 0.7)
            for role in ("option", "menuitemradio", "menuitem", "radio"):
                opt = page.get_by_role(role, name=want)
                if opt.count():
                    opt.first.click(timeout=5_000)
                    page.wait_for_timeout(300)
                    p.pause(page, 0.2, 0.5)
                    chosen.append(f"model {model}")
                    return
            page.keyboard.press("Escape")   # đóng danh sách model, giữ bảng cài đặt
            log.warning("Flow không có model \"%s\" trong danh sách: giữ model hiện tại", model)
        except PlaywrightError as e:
            log.warning("Không chọn được model %s: %s", model, str(e)[:200])

    @staticmethod
    def _settings_button(page, s: dict):
        """Nút mở bảng cài đặt: chip cạnh ô prompt (có biểu tượng crop_…); không thấy thì dùng selector trong selectors.json."""
        chip = page.locator(SETTINGS_CHIP)
        try:
            if chip.count():
                return chip
        except PlaywrightError:
            pass
        return page.locator(s["settings_button"])

    @staticmethod
    def _chip_ok(page, s: dict, kind: str, job: dict) -> bool:
        """
        Đọc chip (không mở bảng): đúng loại (model ảnh / video), đúng model, đúng tỉ lệ và x1 thì coi là đúng.
        Thinking level không hiện trên chip: chỉ tin chip khi môi trường đã được kiểm tra đầy đủ một lần trong phiên này.
        """
        try:
            st = chip_state(FlowBrowserDriver._settings_button(page, s).last.inner_text(timeout=2_000))
        except Exception:   # không đọc được chip: coi như chưa đúng, mở bảng cài đặt kiểm tra
            return False
        low = st["text"].lower()
        model = (job.get("flowModel") or "").strip().lower()
        is_video_chip = "veo" in low or "video" in low
        if (kind == "VIDEO") != is_video_chip:
            return False
        if model and model not in low:
            return False
        if job.get("aspectRatio") and st["ratio"] and st["ratio"] != str(job["aspectRatio"]).strip():
            return False
        return st["count"] == 1

    @staticmethod
    def _apply_settings(page, s: dict, kind: str, job: dict | None = None):
        """
        Bảng cài đặt cạnh ô prompt. Flow có 2 bố cục:
        - Tác nhân (bảng chat bên phải): "Xác nhận trước khi tạo" → "Không bao giờ" (Tác nhân khỏi hỏi lại), số bản tạo
          ảnh / video = x1, rồi bấm Lưu.
        - Tạo trực tiếp (chip "Nano Banana 2.1 · 16:9 · x2"): chọn Image / Video, tỉ lệ khung của phim, x1; bảng tự áp dụng,
          Esc để đóng.
        Mọi bước là "cố gắng": không có nút nào thì bỏ qua, không chờ lâu. Mỗi cú bấm dừng ngẫu nhiên vừa phải như người thật.
        """
        p = pace_mod.Pace(pace_mod.factor(job)) if job else pace_mod.NONE
        btn = FlowBrowserDriver._settings_button(page, s)
        try:
            btn.first.wait_for(state="attached", timeout=8_000)
        except PlaywrightError:
            pass
        if btn.count() == 0:
            log.warning("Không thấy nút cài đặt cạnh ô prompt: giữ cài đặt hiện tại của Flow")
            return
        if kind == "VIDEO" and job and FlowBrowserDriver._chip_matches(btn, job):
            log.info("Cài đặt Flow đã đúng (video, %s, x1): bỏ qua bước mở popover", job.get("videoQuality") or DEFAULT_VIDEO_QUALITY)
            return
        try:
            p.pause(page, 0.4, 1.0)
            btn.last.click(timeout=5_000)

            def radio(pattern: str):
                """Lựa chọn trong bảng cài đặt: radio, không có thì tab / nút cùng tên (Flow vẽ khác nhau theo bố cục)."""
                rx = re.compile(pattern, re.I)
                for role in ("radio", "tab", "menuitemradio", "button"):
                    loc = page.get_by_role(role, name=rx)
                    try:
                        if loc.count():
                            return loc
                    except PlaywrightError:
                        continue
                return page.get_by_role("radio", name=rx)

            def pick(opt, label: str) -> None:
                """Lựa chọn đã đúng thì KHÔNG bấm (thao tác hợp lý như người thật); sai thì bấm sửa, dừng vừa phải trước và sau."""
                if FlowBrowserDriver._is_on(opt):
                    chosen.append(label + " ✓")
                    return
                p.pause(page, 0.35, 0.9)
                opt.click(timeout=5_000)
                page.wait_for_timeout(200)
                p.pause(page, 0.2, 0.5)
                chosen.append(label)
                fixed.append(label)

            chosen: list[str] = []
            fixed: list[str] = []

            try:
                page.get_by_role("radio").first.wait_for(state="visible", timeout=3_000)   # popover mở xong
            except PlaywrightTimeout:
                pass
            page.wait_for_timeout(200)

            p.pause(page, 0.3, 0.7)   # nhìn bảng cài đặt vừa mở
            never = radio(s.get("agent_never_confirm") or r"^\s*(Không bao giờ|Never)(\s|$)")
            if never.count():
                pick(never.first, "không hỏi lại")
            mode = radio(r"^\W*(image\s+)?(Hình ảnh|Image)\s*$" if kind == "IMAGE" else r"^\W*(videocam\s+)?Video\s*$")
            if mode.count():
                pick(mode.first, "ảnh" if kind == "IMAGE" else "video")
                page.wait_for_timeout(100)
            # chọn MÔ HÌNH ngay sau tab Hình ảnh / Video (đổi model có thể làm bảng vẽ lại các lựa chọn bên dưới)
            model = ((job or {}).get("flowModel") or "").strip()
            if model:
                before_n = len(chosen)
                FlowBrowserDriver._pick_model(page, model, p, chosen)
                fixed.extend(c for c in chosen[before_n:] if not c.endswith("(đã đúng)"))
                page.wait_for_timeout(200)
            ratio = (job or {}).get("aspectRatio")
            if ratio:
                # Tỉ lệ khung của phim (16:9, 9:16, 1:1…): tên nút có thể kèm biểu tượng ("crop_16_9 16:9")
                opt = radio(rf"(^|[^0-9]){re.escape(str(ratio).strip())}([^0-9]|$)")
                if opt.count():
                    pick(opt.first, f"tỉ lệ {ratio}")
                else:
                    log.warning("Flow không có lựa chọn tỉ lệ %s: giữ tỉ lệ hiện tại", ratio)
            thinking = ((job or {}).get("flowThinking") or "").strip().lower()
            if kind == "IMAGE" and thinking in THINKING_LEVELS:
                opt = radio(THINKING_LEVELS[thinking])
                if opt.count():
                    pick(opt.first, f"thinking {thinking}")
                else:
                    log.warning("Không thấy lựa chọn Thinking level %s: giữ mặc định", thinking)
            if kind == "VIDEO" and job:
                # Popover bố cục tạo trực tiếp (xác nhận 2026-11-04): nhóm radio độ phân giải (360p/720p) và độ dài (4s/6s/8s/10s).
                # Giá trị Flow không có thì giữ nguyên lựa chọn hiện tại.
                wanted = []
                quality = job.get("videoQuality") or DEFAULT_VIDEO_QUALITY
                # \b thay cho $: nút "360p" kèm biểu tượng ⓘ nên tên đầy đủ là "360p info"
                wanted.append(("độ phân giải", rf"^\s*{re.escape(str(quality))}\b"))
                if job.get("durationSeconds"):
                    wanted.append(("độ dài", rf"^\s*{int(job['durationSeconds'])}\s*s\b"))
                for label, pattern in wanted:
                    opt = radio(pattern)
                    if opt.count():
                        pick(opt.first, label)
                    else:
                        log.warning("Flow không có lựa chọn %s %s: giữ mặc định", label, pattern)
            x1 = radio(r"^\s*x1\s*$")
            if x1.count():
                # bố cục Tác nhân có 2 nhóm x1: nhóm đầu cho ảnh, nhóm sau cho video
                pick(x1.nth(1) if kind == "VIDEO" and x1.count() > 1 else x1.first, "x1")
            page.wait_for_timeout(100)
            p.pause(page, 0.4, 1.0)   # xem lại rồi mới đóng bảng
            save = page.locator(s.get("settings_save") or "button:has-text('Lưu'), button:has-text('Save')")
            if save.count() and save.first.is_visible():
                save.first.click(timeout=5_000)
            else:
                page.keyboard.press("Escape")
            page.wait_for_timeout(600)
            if fixed:
                log.info("Môi trường Flow (%s): đã sửa %s | %s", kind, ", ".join(fixed), ", ".join(chosen))
            else:
                log.info("Môi trường Flow (%s) đã đúng: %s", kind, ", ".join(chosen) or "không có lựa chọn nào")
            return fixed
        except PlaywrightError as e:
            log.warning("Không đặt được cài đặt Flow (x1 / chế độ): %s", str(e)[:200])
            page.keyboard.press("Escape")
        return []

    @staticmethod
    def _listbox_text(page, s: dict) -> str:
        lb = page.locator(s["ingredient_listbox"])
        return lb.first.inner_text() if lb.count() else ""

    @staticmethod
    def _nfc(text: str) -> str:
        return unicodedata.normalize("NFC", text).strip()

    def _listbox_names(self, page, s: dict) -> set[str]:
        lb = page.locator(s["ingredient_listbox"])
        return {self._nfc(x) for x in lb.first.locator("span").all_inner_texts()} if lb.count() else set()

    def _upload_refs(self, page, s: dict, images: list[str], should_stop=None) -> bool:
        """
        Tải lên dự án Flow những ảnh reference CHƯA có (so theo tên file; Flow giữ nguyên tên file khi tải lên).
        Nút "+" cạnh ô prompt mở bảng thành phần; "Tải nội dung nghe nhìn lên" mở hộp chọn file (chọn nhiều file một lần).
        """
        for img in images:
            if not Path(img).is_file():
                raise Rejected(f"Thiếu file ảnh reference: {img}")
        if not images:
            return False
        if should_stop and should_stop():
            raise Cancelled("Người dùng hủy job")
        self._pace.pause(page, 0.5, 1.2)
        page.locator(s["add_reference_button"]).first.click()
        try:
            page.locator(s["ingredient_listbox"]).first.wait_for(state="visible", timeout=3_000)
        except PlaywrightTimeout:
            pass
        page.wait_for_timeout(250)   # danh sách thành phần nạp nội dung
        have = self._listbox_names(page, s)
        need = [img for img in images if self._nfc(Path(img).name) not in have]
        if need:
            log.info("Tải lên Flow %d ảnh: %s", len(need), ", ".join(Path(i).name for i in need))
            self._pace.pause(page, 0.6, 1.4)
            with page.expect_file_chooser() as fc:
                page.locator(s["upload_button"]).first.click()
            fc.value.set_files(need)
            uploading = s.get("uploading_texts") or ["Đang tải lên", "Uploading"]
            deadline = time.monotonic() + 180
            while True:
                if should_stop and should_stop():
                    raise Cancelled("Người dùng hủy job")
                page.wait_for_timeout(500)   # kiểm tra mỗi 500ms để bắt ngay khi tải xong
                text = self._listbox_text(page, s)
                names = self._listbox_names(page, s)
                if not any(u in text for u in uploading) and all(self._nfc(Path(i).name) in names for i in need):
                    break
                if time.monotonic() > deadline:
                    raise TransientError("Quá 180s chưa tải xong ảnh reference lên Flow")
        else:
            log.info("Ảnh reference đã có sẵn trong dự án Flow, không tải lại")
        self._pace.pause(page, 0.4, 1.0)
        page.keyboard.press("Escape")
        page.wait_for_timeout(150)
        self._pace.pause(page, 0.3, 0.8)
        return bool(need)

    @staticmethod
    def _ref_pattern(ids: list[str]):
        return re.compile(r"@(" + "|".join(re.escape(i) for i in ids) + r")(?:\.(?:png|jpe?g|webp))?(?![A-Za-z0-9_-])")

    @classmethod
    def _needed_refs(cls, prompt: str, ref_ids: list[str]) -> set[str]:
        ids = sorted({i for i in ref_ids if i}, key=len, reverse=True)
        return set(cls._ref_pattern(ids).findall(prompt)) if ids else set()

    def _compose_prompt(self, page, s: dict, prompt: str, ref_ids: list[str], mention_all: bool = False) -> set[str]:
        """
        Gõ prompt vào ô của Flow; mỗi "@ID" của ảnh reference thành chip mention:
        Prompt được DÁN thẳng (như Ctrl+V kịch bản vào), còn @tag thì gắn chậm rãi như người thật: dừng, gõ "@", gõ tên ảnh
        từng ký tự, nhìn danh sách gợi ý rồi mới bấm chọn. Nhịp lấy từ self._pace (0 = không dừng).
        """
        p = self._pace
        box = page.locator(s["prompt_box"]).first
        p.pause(page, 0.5, 1.2)
        try:
            box.click(timeout=5_000)
        except PlaywrightError:
            box.focus()
        p.pause(page, 0.2, 0.5)
        page.keyboard.press("Control+A")
        page.keyboard.press("Delete")
        p.pause(page, 0.3, 0.7)

        ids = sorted({i for i in ref_ids if i}, key=len, reverse=True)
        if mention_all:   # chỉ prompt ảnh (biến thể trang phục): prompt video giữ nguyên như cũ
            prompt = self._mention_unmentioned_refs(prompt, ids)
        if not ids:
            page.keyboard.insert_text(prompt)
            p.pause(page, 0.6, 1.5)
            return set()

        pattern = self._ref_pattern(ids)
        option_sel = s.get("ingredient_option") or "button[role=option]"
        pos, attached = 0, set()
        for m in pattern.finditer(prompt):
            # 1. Chèn nhanh đoạn văn bản dài trước @tag (tức thì như Ctrl+V paste)
            if m.start() > pos:
                chunk = prompt[pos:m.start()]
                page.keyboard.insert_text(chunk)

            ref = m.group(1)

            # 2. Gắn @tag chậm rãi: dừng, gõ "@", chờ danh sách hiện, gõ tên ảnh từng ký tự để Flow lọc gợi ý
            p.pause(page, 0.6, 1.4)
            page.keyboard.type("@")
            page.wait_for_timeout(30)
            p.pause(page, 0.4, 0.9)
            p.type_slow(page, ref)

            opt = page.locator(", ".join(f"{lb.strip()} {option_sel}" for lb in s["ingredient_listbox"].split(","))).filter(
                has=page.locator("span", has_text=re.compile(r"^\s*" + re.escape(ref) + r"\.(png|jpe?g|webp)\s*$", re.I)))
            try:
                opt.first.wait_for(state="visible", timeout=5_000)
                p.pause(page, 0.4, 0.9)   # nhìn đúng tên ảnh trong danh sách rồi mới bấm
                human_click(page, opt.first, delay_before=p.click_delay())
                attached.add(ref)
                page.wait_for_timeout(30)
                p.pause(page, 0.4, 1.0)
                self._caret_to_end(page, box)
            except PlaywrightError:
                log.warning("Không thấy ảnh %s trong dự án Flow: để lại @%s dạng chữ", ref, ref)
                page.keyboard.press("Escape")
                page.wait_for_timeout(50)
                self._caret_to_end(page, box, force=True)
                page.keyboard.insert_text("@" + ref)
            pos = m.end()

        # 3. Chèn nhanh phần prompt dài còn lại (tức thì)
        if pos < len(prompt):
            chunk = prompt[pos:]
            page.keyboard.insert_text(chunk)
        p.pause(page, 0.6, 1.5)

        log.info("Đã điền prompt (%d ký tự), gắn chip @tag cho %d ảnh", len(prompt), len(attached))
        return attached

    @classmethod
    def _shown_prompt(cls, job: dict) -> str:
        """Prompt đúng như được gõ vào Flow: prompt ảnh được thêm @ cho ảnh gốc nhắc bằng ID trần."""
        if job["kind"] != "IMAGE":
            return job["prompt"]
        ids = sorted({i for i in cls._ref_ids(job) if i}, key=len, reverse=True)
        return cls._mention_unmentioned_refs(job["prompt"], ids)

    @staticmethod
    def _mention_unmentioned_refs(prompt: str, ids: list[str]) -> str:
        """
        Flow chỉ dùng ảnh đã được @-mention trong ô prompt (ảnh chỉ nằm trong thư viện dự án thì Flow trả lời "tôi cần ảnh gốc,
        hãy @ ảnh đó"). Prompt biến thể trang phục nhắc ảnh gốc bằng ID trần ("…reference image CHAR-CLARA-1…"): biến lần nhắc
        đầu tiên thành @ID; nếu prompt không nhắc ID nào thì thêm @ID vào cuối.
        """
        for ref in ids:
            tag = re.compile(r"@" + re.escape(ref) + r"(?![A-Za-z0-9_-])")
            if tag.search(prompt):
                continue
            bare = re.compile(r"(?<![@A-Za-z0-9_-])" + re.escape(ref) + r"(?![A-Za-z0-9_-])")
            if bare.search(prompt):
                prompt = bare.sub("@" + ref, prompt, count=1)
            else:
                prompt = prompt.rstrip() + " @" + ref
        return prompt

    @staticmethod
    def _caret_to_end(page, box, force: bool = False):
        """Đưa con trỏ về cuối ô prompt (nếu đang ở chỗ khác, ví dụ ô tìm kiếm ảnh)."""
        if force or not box.evaluate("b => b.contains(document.activeElement)"):
            box.focus()
            box.evaluate("""b => { const r = document.createRange(); r.selectNodeContents(b); r.collapse(false);
                const sel = window.getSelection(); sel.removeAllRanges(); sel.addRange(r); }""")
            page.keyboard.press("Control+End")

    @staticmethod
    def _error_counts(page, s: dict) -> dict:
        """Số chỗ đang HIỆN từng câu báo hết lượt / vi phạm trên trang (đếm trước khi bấm Tạo để bỏ qua chữ cũ)."""
        counts = {}
        for key in ("suspicious_texts", "quota_texts", "policy_texts"):
            for text in s.get(key) or []:
                if _is_todo(text):
                    continue
                try:
                    counts[(key, text)] = page.get_by_text(text, exact=False).filter(visible=True).count()
                except PlaywrightError:
                    pass
        return counts

    @classmethod
    def _raise_if_error(cls, page, s: dict, baseline: dict | None = None):
        """Chỉ báo lỗi khi câu báo MỚI xuất hiện so với baseline (chữ cũ trong lịch sử chat không tính)."""
        baseline = baseline or {}
        now = cls._error_counts(page, s)
        for (key, text), n in now.items():
            if n > baseline.get((key, text), 0):
                if key == "suspicious_texts":
                    raise BotSuspected(f"Flow nghi ngờ hoạt động tự động: {text}")
                if key == "quota_texts":
                    raise QuotaExceeded(f"Flow báo: {text}")
                raise Rejected(f"Flow từ chối: {text}")

    # Mỗi ô kết quả có một "khoá": ô ảnh có data-media-id; ô VIDEO của Flow KHÔNG có data-media-id (chỉ có
    # <flow-video-tile> + ảnh thu nhỏ "Generated video thumbnail") -> khoá = "v:" + băm địa chỉ ảnh thu nhỏ.
    # Khoá được gắn lên ô (data-afm-key) để tìm lại ô đó khi bấm tải về.
    _TILE_KEYS_JS = """
        const hash = (t) => { let h = 0; for (let i = 0; i < t.length; i++) { h = (h * 31 + t.charCodeAt(i)) | 0; } return (h >>> 0).toString(36); };
        const keyOf = (c) => {
            const m = c.querySelector('[data-media-id]');
            if (m) return m.getAttribute('data-media-id');
            const th = c.querySelector('flow-video-tile img, img.thumbnail');
            return th && th.getAttribute('src') ? 'v:' + hash(th.getAttribute('src')) : null;
        };
    """

    @staticmethod
    def _media_ids(page, s: dict, kind: str | None = None) -> list[str]:
        """
        Các ô KẾT QUẢ do Flow tạo trong lưới của dự án (khoá của từng ô). Bỏ qua ô ảnh người dùng tải lên
        (tên ô là tên file, ví dụ "CHAR-CALEB-1.png"). kind = "VIDEO": chỉ ô video; "IMAGE": chỉ ô ảnh.
        """
        return page.evaluate("""([card, kind]) => {""" + FlowBrowserDriver._TILE_KEYS_JS + """
            return [...document.querySelectorAll(card)].filter(c => {
                const label = (c.getAttribute('aria-label') || '').trim();
                if (/[.](png|jpe?g|webp|gif|bmp|mp4|mov|webm)$/i.test(label)) return false;   // ảnh / file tải lên
                const isVideo = !!c.querySelector('video, flow-video-tile');
                return kind === 'VIDEO' ? isVideo : kind === 'IMAGE' ? !isVideo : true;
            }).map(c => { const k = keyOf(c); if (k) c.setAttribute('data-afm-key', k); return k; }).filter(Boolean); }""",
                             [s["result_card"], kind])

    @staticmethod
    def _card(page, s: dict, key: str):
        """Ô kết quả theo khoá (gắn lại khoá trước khi tìm: lưới của Flow vẽ lại liên tục)."""
        FlowBrowserDriver._media_ids(page, s)
        return page.locator(f"{s['result_card']}[data-afm-key={json.dumps(key)}]").first

    @staticmethod
    def _media_ready(page, key: str) -> bool:
        """Ảnh / ảnh thu nhỏ của video đã hiện đủ."""
        return page.evaluate("""(key) => { const c = document.querySelector('[data-afm-key="' + CSS.escape(key) + '"]');
            if (!c) return false;
            const img = c.querySelector('img');
            return !!img && img.complete && img.naturalWidth > 0; }""", key)

    def _send(self, page, s: dict, text: str):
        box = page.locator(s["prompt_box"]).first
        box.click()
        human_type(page, box, text)
        page.wait_for_timeout(300)
        send_prompt(page, s)

    def _wait_result(self, page, s: dict, before: set, errors: dict, should_stop, timeout_s: int,
                     kind: str | None = None) -> str:
        """
        Chờ một ô kết quả MỚI (data-media-id chưa có trước khi bấm tạo) hiện đủ ảnh/video. Nếu Tác nhân trả lời xong mà
        chưa tạo (thường là hỏi xác nhận kế hoạch) thì tự gửi agent_auto_reply MỘT lần (không gửi nếu đã có ô mới).
        """
        # Ảnh: chỉ ô ảnh. Video: cấu trúc ô video của Flow chưa chắc chắn -> nhận mọi ô kết quả mới (không phải ảnh
        # tải lên), ưu tiên ô có video; sau khi tải về mới kiểm tra đúng là file video.
        want = "IMAGE" if kind == "IMAGE" else None
        reply_sel = s.get("agent_reply_marker")
        has_reply_sel = not _is_todo(reply_sel)
        replies = page.locator(reply_sel).count() if has_reply_sel else 0
        auto_reply = s.get("agent_auto_reply")
        nudges, idle_since = 0, None
        first_seen: dict[str, float] = {}
        logged = False
        started = time.monotonic()
        deadline = time.monotonic() + timeout_s
        last_log = 0.0
        last_turnstile_check = 0.0
        failed_before = Counter(self._error_tiles(page, s))
        configured = getattr(self, "_tile_retries", None)
        retries_left = max(0, int(configured if configured is not None else os.environ.get("AFM_FLOW_TILE_RETRIES", "10")))
        tries = 0
        while time.monotonic() < deadline:
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            # Ô lỗi trước (chữ "hoạt động bất thường" nằm ngay trên ô): bấm ↻ thử lại, KHÔNG để bước kiểm tra chữ chung của
            # trang báo lỗi trước. Hết lượt thử lại mới báo (tài khoản nghỉ theo Cài đặt).
            new_failed = list((Counter(self._error_tiles(page, s)) - failed_before).elements())
            if new_failed:
                low = new_failed[0].lower()
                hard = any(t.lower() in low for t in (s.get("policy_texts") or []) + (s.get("quota_texts") or []) if not _is_todo(t))
                if not hard and retries_left > 0 and self._retry_error_tile(page, s, failed_before):
                    retries_left -= 1
                    tries += 1
                    log.info("Thử lại lần %d (còn %d lần)", tries, retries_left)
                    # ô vừa bấm thử lại: lần sau nó còn hỏng thì là lỗi mới của lượt thử lại; chữ lỗi cũ trên trang không tính nữa
                    failed_before = Counter(self._error_tiles(page, s))
                    errors = self._error_counts(page, s)
                    deadline = max(deadline, time.monotonic() + timeout_s / 2)
                    continue
                if tries:
                    log.warning("Đã bấm ↻ %d lần mà Flow vẫn báo lỗi: dừng tài khoản này", tries)
                self._raise_for_error_tiles(s, new_failed)
            self._raise_if_error(page, s, errors)
            if time.monotonic() - last_turnstile_check > 5.0:
                last_turnstile_check = time.monotonic()
                detect_and_solve_turnstile(page, timeout_s=0.5)
            # Video xong: Flow tự chuyển sang /project/<id>/edit/<media id> (không còn lưới ô) -> media id lấy từ địa chỉ
            edit_id = edit_media_id(page.url)
            if edit_id and edit_id not in before:
                log.info("Flow mở màn chỉnh sửa kết quả mới: %s", edit_id)
                page.wait_for_timeout(1_500)
                return edit_id
            new = [m for m in self._media_ids(page, s, want) if m and m not in before]
            if kind == "VIDEO" and new:
                videos = [m for m in self._media_ids(page, s, "VIDEO") if m in new]
                new = videos or new
            any_new = new or [m for m in self._media_ids(page, s) if m and m not in before]
            for m in new:
                first_seen.setdefault(m, time.monotonic())
            if not logged and time.monotonic() - started > 180:
                logged = True
                self._log_tiles(page, s, before)   # chẩn đoán: chờ lâu mà chưa nhận kết quả
            if new:
                # video: thẻ <video> chỉ nạp file khi người dùng bấm xem -> ô mới đứng yên 30s, không còn % tiến độ = xong
                settled = (kind == "VIDEO" and time.monotonic() - first_seen[new[0]] > 30
                           and not self._tile_busy(page, s, new[0]))
                if settled or self._media_ready(page, new[0]):
                    page.wait_for_timeout(1_500 if kind == "VIDEO" else 1_000)
                    log.info("Có kết quả mới trên Flow: %s", new[0])
                    return new[0]
            elif not any_new and has_reply_sel and auto_reply and page.locator(reply_sel).count() > replies:
                # Tác nhân vừa trả lời xong một lượt mà chưa có ô kết quả mới
                idle_since = idle_since or time.monotonic()
                if time.monotonic() - idle_since > 8:
                    if nudges >= 1:
                        idle_since = None
                        replies = page.locator(reply_sel).count()
                        page.wait_for_timeout(4_000)
                        continue
                    log.info("Tác nhân Flow hỏi lại: tự trả lời \"%s\"", auto_reply)
                    self._send(page, s, auto_reply)
                    nudges += 1
                    replies, idle_since = page.locator(reply_sel).count(), None
            if time.monotonic() - last_log > 60:
                log.info("Đang chờ Flow tạo...")
                last_log = time.monotonic()
            page.wait_for_timeout(1_500)
        self._log_tiles(page, s, before)
        raise TransientError(f"Quá {timeout_s}s chưa có kết quả")

    # Ô Flow tạo HỎNG (đã thấy 2026-11-06 trên ô video: <flow-error-tile>, không có data-media-id / ảnh thu nhỏ nên
    # _media_ids không bao giờ thấy nó): nhãn + chữ trên ô, để so với trước khi bấm Tạo.
    _ERROR_TILES_JS = """(card) => [...document.querySelectorAll(card)].filter(c => c.querySelector('flow-error-tile')
            || /^\\s*(\\S{0,12}\\s*)?(failed\\b|không thành công)/i.test(c.innerText || ''))
        .map(c => ((c.getAttribute('aria-label') || '').trim() + ' | ' + (c.innerText || '').replace(/\\s+/g, ' ').trim()).slice(0, 400))"""

    @classmethod
    def _error_tiles(cls, page, s: dict) -> list[str]:
        try:
            return page.evaluate(cls._ERROR_TILES_JS, s["result_card"]) or []
        except PlaywrightError:
            return []

    # Bấm nút ↻ "Thử lại" trên ô lỗi MỚI (ô đầu tiên chưa có trước khi bấm Tạo). Nút không có chữ: tìm theo aria-label / tooltip
    # (Thử lại, Retry, Tạo lại…), theo tên biểu tượng (refresh, replay, autorenew), không thấy thì lấy nút đầu tiên của ô
    # (thứ tự trên ô: ↻ thử lại, ↩ dùng lại câu lệnh, 🗑 xóa).
    _RETRY_TILE_JS = """([card, before]) => {
        const seen = new Map(); before.forEach(t => seen.set(t, (seen.get(t) || 0) + 1));
        const tiles = [...document.querySelectorAll(card)].filter(c => c.querySelector('flow-error-tile')
            || /^\\s*(\\S{0,12}\\s*)?(failed\\b|không thành công)/i.test(c.innerText || ''));
        for (const c of tiles) {
            const key = ((c.getAttribute('aria-label') || '').trim() + ' | ' + (c.innerText || '').replace(/\\s+/g, ' ').trim()).slice(0, 400);
            if (seen.get(key)) { seen.set(key, seen.get(key) - 1); continue; }
            const btns = [...c.querySelectorAll('button')];
            const name = b => ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.innerText || '')).toLowerCase();
            const retry = btns.find(b => /thử lại|retry|try again|tạo lại|regenerate|refresh|replay|autorenew|rotate/.test(name(b)))
                || btns.find(b => !/xóa|xoá|delete|trash|reuse|dùng lại|undo|redo/.test(name(b))) || null;
            if (!retry) return null;
            document.querySelectorAll('[data-afm-retry]').forEach(x => x.removeAttribute('data-afm-retry'));
            retry.setAttribute('data-afm-retry', '1');
            return key;
        }
        return null; }"""

    def _retry_error_tile(self, page, s: dict, before: Counter) -> bool:
        """Có ô lỗi mới: bấm ↻ thử lại trên chính ô đó (như người thật: nhìn thấy lỗi, chờ một chút rồi bấm). True nếu đã bấm."""
        try:
            key = page.evaluate(self._RETRY_TILE_JS, [s["result_card"], list(before.elements())])
        except PlaywrightError:
            return False
        if not key:
            return False
        self._pace.pause(page, 2.5, 6.0)
        btn = page.locator("[data-afm-retry='1']").first
        try:
            btn.scroll_into_view_if_needed(timeout=3_000)
            human_click(page, btn, delay_before=self._pace.click_delay())
        except PlaywrightError as e:
            if self._page_gone(page, e):
                # tab / cửa sổ Flow bị đóng giữa chừng: không phải lỗi của prompt, dừng phim chờ người dùng mở lại
                raise TabLost("Tab Flow đã bị đóng trong lúc chờ kết quả: mở lại Chrome + tab Flow rồi bấm Chạy") from e
            log.warning("Không bấm được nút thử lại trên ô lỗi: %s", str(e)[:200])
            return False
        log.info("Ô lỗi trên Flow: đã bấm ↻ thử lại (%s)", key[:160])
        page.wait_for_timeout(1_500)
        return True

    @staticmethod
    def _page_gone(page, e: Exception | None = None) -> bool:
        """Tab / cửa sổ đã đóng (người dùng đóng Chrome, tab bị tắt)."""
        if e is not None and "has been closed" in str(e):
            return True
        try:
            return bool(page.is_closed())
        except Exception:  # noqa: BLE001
            return False

    @classmethod
    def _raise_if_error_tile(cls, page, s: dict, before: Counter):
        """Có ô lỗi MỚI sau khi bấm Tạo: Flow không tạo được cảnh này -> báo ngay thay vì chờ tới hết giờ."""
        cls._raise_for_error_tiles(s, list((Counter(cls._error_tiles(page, s)) - before).elements()))

    @classmethod
    def _raise_for_error_tiles(cls, s: dict, new: list[str]):
        if not new:
            return
        text = new[0]
        low = text.lower()
        log.warning("Flow tạo hỏng (ô lỗi): %s", text)
        if any(t.lower() in low for t in (s.get("policy_texts") or []) if not _is_todo(t)):
            raise Rejected(f"Flow từ chối (vi phạm chính sách): {text}")
        if any(t.lower() in low for t in (s.get("quota_texts") or []) if not _is_todo(t)):
            raise QuotaExceeded(f"Flow báo: {text}")
        if any(t.lower() in low for t in (s.get("suspicious_texts") or []) if not _is_todo(t)):
            raise BotSuspected(f"Flow nghi ngờ hoạt động tự động: {text}")
        raise TransientError(f"Flow tạo hỏng: {text}")

    @staticmethod
    def _tile_busy(page, s: dict, media_id: str) -> bool:
        """Ô đang tạo: có chữ phần trăm / thanh tiến độ."""
        try:
            return page.evaluate("""([card, id]) => { const c = document.querySelector('[data-afm-key="' + CSS.escape(id) + '"]');
                if (!c) return true;
                return /[0-9]+ *%/.test(c.innerText || '') || !!c.querySelector('[role=progressbar], mat-progress-spinner, mat-spinner'); }""",
                                 [s["result_card"], media_id])
        except PlaywrightError:
            return True

    @staticmethod
    def _log_tiles(page, s: dict, before: set):
        """Ghi cấu trúc các ô trong lưới vào log (để sửa selector khi Flow đổi giao diện)."""
        try:
            tiles = page.evaluate("""(card) => [...document.querySelectorAll(card)].map(c => {
                const m = c.querySelector('[data-media-id]');
                return {label: c.getAttribute('aria-label'), media: m && m.getAttribute('data-media-id'), mediaTag: m && m.tagName,
                        tags: [...new Set([...c.querySelectorAll('*')].map(e => e.tagName.toLowerCase()).filter(t => t.includes('-') || t === 'video'))].slice(0, 12)};
            })""", s["result_card"])
            for t in tiles:
                log.warning("Ô trên Flow: %s%s", t, "" if t.get("media") in before else "  <- MỚI")
        except PlaywrightError as e:
            log.warning("Không đọc được lưới Flow: %s", e)

    def _click_download(self, page, s: dict, media_id: str, kind: str, quality: str | None):
        """
        Ô kết quả → "Tuỳ chọn khác" → "Tải xuống" → chọn chất lượng (ảnh: 1K = kích thước gốc; video: 480p/720p/1080p).
        Đang ở màn chỉnh sửa (/edit/<media id>, Flow tự mở sau khi tạo video): nút tải ở thanh trên cùng.
        """
        q = quality if kind == "VIDEO" else (s.get("image_download_quality") or "1K")
        option_tpl = s.get("download_quality_option")
        if edit_media_id(page.url) == media_id:
            btn = page.locator(s.get("editor_download_button")
                               or "button:has(mat-icon:text-is('download')), [aria-label='Tải xuống'], [aria-label='Download']").first
            btn.wait_for(state="visible", timeout=15_000)
            btn.click()
            page.wait_for_timeout(1_000)
            item = None
        else:
            card = self._card(page, s, media_id)
            card.scroll_into_view_if_needed()
            card.hover()
            page.wait_for_timeout(500)
            menu_btn = card.locator(s["tile_menu_button"]).first
            try:
                menu_btn.click(timeout=10_000)
            except PlaywrightTimeout:   # nút bị lớp khác che (hộp thoại / câu hỏi của Tác nhân): bấm ép
                menu_btn.click(timeout=10_000, force=True)
            page.wait_for_timeout(800)
            item = page.locator(s["download_menu_item"]).first
            item.hover()
            page.wait_for_timeout(800)
        if _is_todo(option_tpl) or not q:
            if item is not None:
                item.click()  # không cấu hình menu chất lượng: bấm thẳng "Tải xuống"
            return   # ở màn chỉnh sửa: bấm nút tải là tải luôn
        levels = VIDEO_QUALITIES[VIDEO_QUALITIES.index(q):] if q in VIDEO_QUALITIES else [q]   # không có mức chọn thì thử mức thấp hơn
        for level in levels:
            option = page.locator(option_tpl.replace("{quality}", level)).first
            if option.count():
                if level != q:
                    log.warning("Menu tải về không có '%s': tải mức '%s'", q, level)
                option.click()
                return
        if item is None:
            return   # ở màn chỉnh sửa không có menu chất lượng
        first = page.locator("[role=menu]").last.locator("[role=menuitem]:not([disabled])").filter(has=page.locator(".label")).first
        if not first.count():
            raise TransientError(f"Menu tải về của Flow không có mục nào (muốn '{q}')")
        log.warning("Menu tải về không có '%s' hay mức thấp hơn: bấm mục đầu tiên của menu con", q)
        first.click()

    @staticmethod
    def _save_download(d, target: Path):
        """Ghi vào .part rồi đổi tên: không để lại file dở dang; file rỗng coi là lỗi."""
        part = target.with_name(target.name + ".part")
        try:
            d.save_as(part)
            if not part.is_file() or part.stat().st_size == 0:
                raise TransientError("File tải về từ Flow bị rỗng")
            os.replace(part, target)
        finally:
            part.unlink(missing_ok=True)

    def _download(self, page, s: dict, media_id: str, staging: Path, stem: str, kind: str, quality: str | None,
                  should_stop=None) -> Path:
        got: list = []
        on_dl = got.append
        page.on("download", on_dl)
        try:
            self._click_download(page, s, media_id, kind, quality)
            deadline = time.monotonic() + 180
            while not got:
                if should_stop and should_stop():
                    raise Cancelled("Người dùng hủy job")
                if time.monotonic() > deadline:
                    raise TransientError("Quá 180s Flow chưa bắt đầu tải file")
                page.wait_for_timeout(500)
        finally:
            page.remove_listener("download", on_dl)
        d = got[0]
        suffix = Path(d.suggested_filename).suffix or (".mp4" if kind == "VIDEO" else ".png")
        is_video_file = suffix.lower() in (".mp4", ".mov", ".webm")
        if (kind == "VIDEO") != is_video_file:
            raise TransientError(f"Flow trả về file {suffix} cho cảnh {'video' if kind == 'VIDEO' else 'ảnh'}: tạo lại")
        target = staging / f"{stem}{suffix.lower()}"
        self._save_download(d, target)
        return target

    # ---------- nhớ lần bấm Tạo dở dang (đóng cửa sổ / mất kết nối khi Flow đang tạo) ----------
    @staticmethod
    def _pending_file(job: dict) -> Path:
        # một file cho mỗi tài khoản: mỗi tài khoản chỉ chạy một cảnh một lúc, lần bấm Tạo mới nhất ghi đè lần trước
        return Path(job["stagingDir"]).parents[1] / f"flow-pending-acc{job['account'].get('id')}.json"

    def _save_pending(self, page, job: dict, before: set):
        try:
            self._pending_file(job).write_text(json.dumps({
                "jobId": job.get("jobId"), "revStaging": job["stagingDir"], "project": project_url(page.url),
                "before": sorted(before), "at": time.time()}), encoding="utf-8")
        except OSError as e:
            log.warning("Không ghi được file nhớ lần tạo: %s", e)

    def _clear_pending(self, job: dict):
        f = self._pending_file(job)
        try:
            if f.exists() and json.loads(f.read_text(encoding="utf-8")).get("revStaging") == job["stagingDir"]:
                f.unlink()
        except (OSError, ValueError):
            pass

    def _pending_result(self, page, s: dict, job: dict) -> str | None:
        """
        Lần trước của CHÍNH cảnh này (cùng revision prompt) đã bấm Tạo trên dự án này nhưng chưa kịp tải về, và từ đó
        tài khoản chưa tạo gì khác: ô kết quả mới trong dự án là của cảnh này -> tải về, không tạo lại (đỡ tốn lượt).
        """
        f = self._pending_file(job)
        try:
            d = json.loads(f.read_text(encoding="utf-8")) if f.exists() else None
        except (OSError, ValueError):
            return None
        if d and d.get("revStaging") != job["stagingDir"]:
            # tài khoản này đã sang cảnh khác: ô mới trong dự án có thể là của cảnh đó, bỏ dấu cũ cho an toàn
            try:
                f.unlink()
            except OSError:
                pass
            return None
        if (not d or d.get("project") != project_url(page.url)
                or time.time() - d.get("at", 0) > 24 * 3600):
            return None
        page.wait_for_timeout(2_000)   # chờ lưới ảnh của dự án hiện ra
        new = [m for m in self._media_ids(page, s, "IMAGE" if job["kind"] == "IMAGE" else None) if m and m not in set(d.get("before") or [])]
        if new and self._media_ready(page, new[0]):
            log.info("Cảnh %s: lần tạo trước đã có kết quả trên Flow (%s), tải về thay vì tạo lại", job["externalId"], new[0])
            return new[0]
        return None

    @staticmethod
    def _ref_ids(job: dict) -> list[str]:
        return [Path(p).stem for p in job.get("referenceImages") or []]

    @staticmethod
    def _raise_if_login(page, job: dict):
        """Phiên hết hạn phát hiện muộn: Flow chuyển sang trang đăng nhập Google sau khi mở dự án."""
        if "accounts.google.com" in page.url:
            raise AuthRequired(f"{job['account']['email']}: chưa đăng nhập hoặc phiên đã hết hạn")

    @staticmethod
    def _wait_prompt_box(page, s: dict):
        try:
            page.locator(s["prompt_box"]).first.wait_for(state="visible", timeout=30_000)
        except PlaywrightTimeout as e:
            raise TransientError("Không thấy ô prompt trong dự án Flow") from e

    # ---------- BACKGROUND: worker tự làm hết ----------
    _pace = pace_mod.NONE

    @staticmethod
    def _env_key(page, job: dict) -> tuple:
        return (project_url(page.url), job["kind"], job.get("flowModel"), job.get("aspectRatio"),
                job.get("flowThinking"), job.get("videoQuality"), job.get("durationSeconds"))

    def _setup_env_once(self, page, s: dict, job: dict) -> tuple:
        """Lần đầu với dự án / loại / model này: cài môi trường đầy đủ. Trả về env_key."""
        env_key = self._env_key(page, job)
        if env_key not in _ENV_READY:
            self._apply_settings(page, s, job["kind"], job)
            _ENV_READY.add(env_key)
            self._pace.pause(page, 0.5, 1.2)
        return env_key

    def _check_env_before_send(self, page, s: dict, job: dict, env_key: tuple):
        """Mỗi lần gửi: nhìn chip (model, tỉ lệ, x1); đúng thì gửi luôn, sai thì mở bảng cài đặt sửa đúng mục sai."""
        self._pace.pause(page, 0.6, 1.4)
        if env_key in _ENV_READY and self._chip_ok(page, s, job["kind"], job):
            log.info("Môi trường Flow (%s) đúng theo chip: gửi luôn", job["kind"])
        else:
            self._apply_settings(page, s, job["kind"], job)
            _ENV_READY.add(env_key)

    def generate(self, job: dict, should_stop) -> GenerationResult:
        self._pace = pace_mod.Pace(pace_mod.factor(job))   # nhịp thao tác như người thật (Cài đặt → Tốc độ thao tác)
        self._tile_retries = job.get("errorTileRetries")   # Cài đặt → số lần bấm ↻ trên ô lỗi
        s = self._sel(BACKGROUND_KEYS)
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        tm = StepTimer()
        try:
            t_launch = time.monotonic()
            with self._browser(job["account"], headless=job.get("headless", False)) as ctx:
                tm.steps.append(("mo_edge", time.monotonic() - t_launch))
                try:
                    with tm.step("vao_du_an"):
                        page = self._open_flow(ctx, job)
                        try:
                            self._ensure_project(page, job, strict=True)
                            if "/project/" not in page.url:
                                raise TransientError("Chưa vào được dự án trên Flow")
                            self._wait_prompt_box(page, s)
                            self._dismiss_popups(page)   # hộp thoại phủ (ví dụ Changelog) chặn mọi cú bấm phía sau
                        except TransientError:
                            self._raise_if_login(page, job)
                            raise
                    if job.get("editInstruction") and job.get("editSourceUrl"):
                        return self._edit_on_flow(page, s, job, staging, should_stop)
                    with tm.step("cai_dat"):
                        self._pace.pause(page, 1.5, 4.0)   # như người chuyển sang mục mới: nhìn màn hình một chút rồi mới làm
                        env_key = self._setup_env_once(page, s, job)   # lần đầu với dự án / loại này: cài môi trường đầy đủ
                        self._select_mode(page, s, job["kind"])
                    with tm.step("kiem_tra_lan_truoc"):
                        media_id = self._pending_result(page, s, job)
                    if not media_id:
                        with tm.step("upload_anh"):
                            uploaded = self._upload_refs(page, s, job["referenceImages"], should_stop)
                            # ô ảnh vừa tải lên hiện trong lưới chậm vài giây; không upload gì thì không cần chờ
                            page.wait_for_timeout(1_500 if uploaded else 150)
                        before = set(self._media_ids(page, s))
                        errors = self._error_counts(page, s)
                        with tm.step("go_prompt"):
                            attached = self._compose_prompt(page, s, job["prompt"], self._ref_ids(job), mention_all=job["kind"] == "IMAGE")
                        need = self._needed_refs(self._shown_prompt(job), self._ref_ids(job))
                        if need - attached:
                            raise TransientError(f"Chỉ gắn được {len(attached & need)}/{len(need)} ảnh @tag vào prompt: không bấm Tạo")
                        if should_stop():
                            raise Cancelled("Người dùng hủy job")
                        with tm.step("kiem_tra_moi_truong"):
                            # mỗi lần gửi: nhìn chip trước (model, tỉ lệ, x1). Đúng và môi trường đã kiểm tra trong phiên này
                            # thì gửi luôn; sai thì mở bảng cài đặt, chọn model trước rồi sửa đúng mục sai (thao tác vừa phải)
                            self._check_env_before_send(page, s, job, env_key)
                        self._save_pending(page, job, before)
                        self._pace.pause(page, 1.0, 2.5)   # đọc lại prompt rồi mới bấm Tạo
                        send_prompt(page, s)
                        with tm.step("cho_google"):
                            media_id = self._wait_result(page, s, before, errors, should_stop, job["timeoutSeconds"], job["kind"])
                    with tm.step("tai_ve"):
                        staged = self._download(page, s, media_id, staging, job["externalId"], job["kind"],
                                                job.get("videoQuality") or DEFAULT_VIDEO_QUALITY, should_stop)
                    self._clear_pending(job)
                    with tm.step("lay_link_video"):
                        media_url = self._media_url(page, s, media_id) if job["kind"] == "VIDEO" else None
                    return GenerationResult(staged_file=staged, remote_ref=project_url(page.url), media_url=media_url)
                except (PlaywrightTimeout, AssertionError) as e:  # AssertionError: expect(...) hết giờ
                    raise TransientError(f"Hết thời gian chờ một phần tử trên Flow: {str(e)[:500]}") from e
        finally:
            log.info("Thời gian job %s %s: %s", job.get("kind"), job.get("externalId"), tm.summary())

    @staticmethod
    def _dismiss_popups(page) -> bool:
        """
        Flow đôi khi hiện hộp thoại phủ lên trang (ví dụ "Changelog" là một iframe trong .cdk-overlay-container) làm MỌI cú bấm
        phía sau bị chặn ("subtree intercepts pointer events"). Đóng bằng Esc, không được thì bấm nút Đóng. True nếu có hộp thoại.
        """
        overlay = page.locator(".cdk-overlay-container iframe, .cdk-overlay-container [role=dialog], .cdk-overlay-container mat-dialog-container")
        try:
            if overlay.count() == 0:
                return False
            for _ in range(3):
                page.keyboard.press("Escape")
                page.wait_for_timeout(400)
                if overlay.count() == 0:
                    return True
            close = page.locator(".cdk-overlay-container").get_by_role(
                "button", name=re.compile(r"^\s*(close|đóng|got it|dismiss|skip|no thanks|ok)\b", re.I))
            if close.count():
                close.first.click(timeout=3_000)
                page.wait_for_timeout(400)
            log.info("Đã đóng hộp thoại phủ trên Flow")
            return True
        except PlaywrightError as e:
            log.warning("Không đóng được hộp thoại phủ: %s", str(e)[:150])
            return False

    def run_session(self, first_job: dict, claim_more, on_start, on_done, on_fail, stop_of) -> None:
        """
        Chạy SONG SONG nhiều lệnh trên tài khoản này trong một lần mở trình duyệt (docs/SONG-SONG.md). first_job đã được backend giao;
        claim_more() xin thêm job cho cùng tài khoản (None khi hết việc / đủ chỗ). Kết quả báo QUA callback ngay khi từng lệnh
        xong. Lỗi trước khi phiên bắt đầu (mở trình duyệt, vào dự án) được ném ra: worker báo lỗi cho first_job.
        """
        s = self._sel(BACKGROUND_KEYS)
        capacity = int((first_job["account"] or {}).get("maxConcurrent") or 1)
        with self._browser(first_job["account"], headless=first_job.get("headless", False)) as ctx:
            try:
                page = self._open_flow(ctx, first_job)
                self._ensure_project(page, first_job, strict=True)
                if "/project/" not in page.url:
                    raise TransientError("Chưa vào được dự án trên Flow")
                self._wait_prompt_box(page, s)
                page.wait_for_timeout(1_500)
                self._dismiss_popups(page)
            except (PlaywrightTimeout, AssertionError) as e:
                raise TransientError(f"Hết thời gian chờ một phần tử trên Flow: {str(e)[:500]}") from e
            log.info("Phiên Flow song song: tối đa %d lệnh cùng lúc trên %s", capacity, first_job["account"].get("email"))
            gap = float(os.environ.get("AFM_GAP_S", "8"))   # cộng thêm ngẫu nhiên trong SessionRunner;   # giãn nhịp giữa 2 lần gửi lệnh (giây); main cũ chạy tuần tự nên cách nhau cả phút
            SessionRunner(_SessionOps(self, page, s, ctx), capacity, claim_more, on_start, on_done, on_fail, stop_of,
                          gap_s=gap).run(first_job)

    def _media_url(self, page, s: dict, media_id: str) -> str | None:
        """Địa chỉ màn chỉnh sửa của kết quả (…/project/<id>/edit/<media>): mở ô kết quả rồi đọc địa chỉ, quay lại lưới."""
        if edit_media_id(page.url):
            return page.url.split("?")[0]
        try:
            grid = page.url
            self._card(page, s, media_id)   # gắn lại khoá lên các ô
            opened = page.evaluate("""(key) => { const c = document.querySelector('[data-afm-key="' + CSS.escape(key) + '"]');
                if (!c) return false; const t = c.querySelector('img') || c; t.click(); return true; }""", media_id)
            if not opened:
                return None
            page.wait_for_url(re.compile(r"/edit/"), timeout=15_000)
            url = page.url.split("?")[0]
            page.goto(grid, wait_until="domcontentloaded")
            return url
        except PlaywrightError as e:
            log.warning("Không lấy được địa chỉ chỉnh sửa trên Flow: %s", str(e)[:200])
            return None

    def _edit_on_flow(self, page, s: dict, job: dict, staging: Path, should_stop) -> GenerationResult:
        """
        "Sửa trên Flow": mở màn chỉnh sửa của video cũ, gõ yêu cầu sửa vào ô "Mô tả cách chỉnh sửa video này",
        bấm tạo; bản sửa xuất hiện thành một ô video mới trong lưới của dự án -> tải về như video thường.
        """
        grid = project_url(page.url)
        page.wait_for_timeout(3_000)
        before = set(self._media_ids(page, s))
        errors = self._error_counts(page, s)
        page.goto(job["editSourceUrl"], wait_until="domcontentloaded")
        box = page.locator(s.get("edit_prompt_box") or "[role=textbox][contenteditable=true]").first
        try:
            box.wait_for(state="visible", timeout=30_000)
        except PlaywrightTimeout as e:
            raise TransientError("Không mở được màn chỉnh sửa video trên Flow (video cũ đã bị xóa?)") from e
        box.click()
        page.keyboard.press("Control+A")
        page.keyboard.press("Delete")
        human_type(page, box, job["editInstruction"])
        page.wait_for_timeout(500)
        log.info("Sửa trên Flow %s: %s", job["externalId"], job["editInstruction"])
        self._save_pending(page, job, before)
        page.locator(s["generate_button"]).last.click()
        page.wait_for_timeout(15_000)   # để Flow nhận yêu cầu rồi mới rời màn chỉnh sửa
        page.goto(grid, wait_until="domcontentloaded")
        page.wait_for_timeout(5_000)
        media_id = self._wait_result(page, s, before, errors, should_stop, job["timeoutSeconds"], "VIDEO")
        staged = self._download(page, s, media_id, staging, job["externalId"], "VIDEO", job.get("videoQuality") or DEFAULT_VIDEO_QUALITY,
                                should_stop)
        self._clear_pending(job)
        return GenerationResult(staged_file=staged, remote_ref=grid, media_url=self._media_url(page, s, media_id))

    # ---------- FOREGROUND: tự upload + gõ prompt có @tag, người dùng bấm Tạo; hook bắt file tải về ----------
    def generate_foreground(self, job: dict, should_stop) -> GenerationResult:
        s = self._sel([])
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        captured: list[Path] = []
        failures: list[str] = []
        page_ref: list = []

        def on_download(d):
            try:
                handle(d)
            except Exception as e:  # lỗi trong handler không được làm vòng chờ treo mãi
                log.warning("Lưu file tải về lỗi: %s", e)
                failures.append(str(e)[:200] or type(e).__name__)

        def handle(d):
            suffix = Path(d.suggested_filename).suffix or (".mp4" if job["kind"] == "VIDEO" else ".png")
            if (job["kind"] == "VIDEO") != (suffix.lower() in (".mp4", ".mov", ".webm")):
                log.warning("Bỏ qua file %s: cảnh %s cần %s", d.suggested_filename, job["externalId"],
                            "video" if job["kind"] == "VIDEO" else "ảnh")
                _status(page_ref[0], "File vừa tải không phải " + ("video" if job["kind"] == "VIDEO" else "ảnh")
                        + ": hãy tải đúng kết quả của cảnh này") if page_ref else None
                return
            target = staging / f"{job['externalId']}{suffix.lower()}"
            self._save_download(d, target)
            captured.append(target)
            log.info("Bắt được file người dùng tải: %s", target)

        # Dự phòng khi tự gắn ảnh lỗi: thư mục ảnh đánh số theo thứ tự gắn + hộp nổi trên trang Flow
        refs_folder = prepare_refs_folder(job)
        # Nút trên hộp hướng dẫn gọi về Python qua binding: "pause" = tạm dừng phim, "skip" = bỏ qua cảnh
        user_action: list[str] = []
        with self._browser(job["account"], headless=False) as ctx:
            ctx.on("download", on_download)  # mọi lần bấm Download trên Flow trong cửa sổ này
            try:
                ctx.grant_permissions(["clipboard-read", "clipboard-write"], origin="https://flow.google.com")
            except PlaywrightError:
                pass
            if not hasattr(ctx, "_afm_handlers"):
                ctx._afm_handlers = []
                try:
                    ctx.expose_binding("afmAction", lambda source, action: [h(str(action)) for h in list(getattr(ctx, "_afm_handlers", []))])
                except PlaywrightError:
                    pass
            ctx._afm_handlers.append(user_action.append)
            ctx.add_init_script(overlay_script(job, refs_folder))
            page = self._open_flow(ctx, job)
            page_ref.append(page)
            try:
                refs = [Path(p).stem for p in job.get("referenceImages") or []]
                title = (f"Video STT {job['externalId']}" if job["kind"] == "VIDEO" else f"Ảnh {job['externalId']}")
                overlay_data = {
                    "title": title,
                    "kind": job["kind"],
                    "refs": refs,
                    "refsFolder": str(refs_folder) if refs_folder else "",
                    "prompt": job.get("prompt") or "",
                    "duration": job.get("durationSeconds"),
                    "quality": job.get("videoQuality"),
                }
                page.evaluate("d => { if (window.__afmUpdate) window.__afmUpdate(d); else window.__afmData = d; }", overlay_data)
            except Exception:
                pass
            self._ensure_project(page, job)
            self._raise_if_login(page, job)
            resume = None
            if "/project/" in page.url:
                try:
                    self._wait_prompt_box(page, s)
                    resume = self._pending_result(page, s, job)
                except (TransientError, PlaywrightError):
                    resume = None
            if resume:
                filled = True
            else:
                _status(page, "App đang tự điền, đừng đóng cửa sổ…")
                filled = self._prefill_best_effort(page, s, job, should_stop)
                if not filled and refs_folder:
                    open_in_explorer(refs_folder)  # tự gắn không được: mở thư mục ảnh để người dùng kéo tay
            # Điền sẵn prompt và gắn ảnh @tag; để người dùng tự kiểm tra và bấm Tạo theo yêu cầu.
            # Chỉ auto khi resume (ảnh/video lần trước đã tạo xong cần tải về).
            auto = bool(resume)
            state = {"last_check": time.monotonic(), "errors": self._error_counts(page, s)}

            def check():
                if should_stop():
                    raise Cancelled("Người dùng hủy job")
                if "pause" in user_action:
                    raise PausedByUser(f"Bạn bấm \"Tạm dừng phim\" ở cảnh {job['externalId']}")
                if "skip" in user_action:
                    raise Cancelled(f"Bạn bấm \"Bỏ qua cảnh này\" ở cảnh {job['externalId']}")
                if "quota" in user_action:
                    raise QuotaExceeded(f"Bạn báo tài khoản {job['account']['email']} hết lượt ở cảnh {job['externalId']}")
                if not ctx.pages:
                    raise TabLost(f"Bạn đã đóng cửa sổ Flow ở cảnh {job['externalId']}")
                if failures:
                    raise TransientError(f"Không lưu được file tải về: {failures[0]}")
                if time.monotonic() - state["last_check"] > 5:
                    state["last_check"] = time.monotonic()
                    self._raise_if_error(ctx.pages[0], s, state["errors"])   # Flow báo hết lượt → tự đổi tài khoản

            try:
                if auto:
                    page.set_default_timeout(45_000)
                    try:
                        if resume:
                            media_id = resume
                            _status(page, "✓ Kết quả của lần trước đã có trên Flow, đang tải về…")
                            _status(page, f"✓ Có {'video' if job['kind'] == 'VIDEO' else 'ảnh'}, đang tải về…")
                            self._click_download(page, s, media_id, job["kind"], job.get("videoQuality") or DEFAULT_VIDEO_QUALITY)
                    except (TransientError, PlaywrightTimeout) as e:
                        log.warning("FOREGROUND %s: không tự lấy được kết quả: %s", job["externalId"], e)
                        _status(page, f"Chưa tự lấy được {'video' if job['kind'] == 'VIDEO' else 'ảnh'}: khi có kết quả, bấm ⋮ → Tải xuống")
                else:
                    log.info("FOREGROUND %s: đã điền đủ prompt + @tag, chờ người dùng bấm Tạo", job["externalId"])
                    if filled:
                        _status(page, "✓ Đã điền xong prompt và gắn @tag. Mời bạn kiểm tra và bấm nút Tạo!")

                before = set(self._media_ids(page, s) or [])
                generating = False
                while not captured:
                    check()
                    if "generate_now" in user_action:
                        user_action.remove("generate_now")
                        _status(page, "Đang gửi prompt và bấm Tạo…")
                        send_prompt(page, s)
                        generating = True

                    # Tự động nhận diện khi người dùng đã bấm Tạo hoặc có kết quả mới xuất hiện
                    edit_id = edit_media_id(page.url)
                    if edit_id and edit_id not in before:
                        _status(page, f"✓ Đã xong {'video' if job['kind'] == 'VIDEO' else 'ảnh'}, đang tải về…")
                        try:
                            self._click_download(page, s, edit_id, job["kind"], job.get("videoQuality") or DEFAULT_VIDEO_QUALITY)
                        except Exception as e:
                            log.warning("Chưa tự bấm tải được trên trang chỉnh sửa: %s", e)
                    else:
                        current_ids = set(self._media_ids(page, s) or [])
                        new_ids = [m for m in current_ids if m and m not in before]
                        if new_ids:
                            if not generating:
                                generating = True
                                _status(page, f"Đang tạo {'video' if job['kind'] == 'VIDEO' else 'ảnh'}… Bạn có thể bấm Tải xuống khi xong (hoặc app tự tải).")
                            for mid in new_ids:
                                try:
                                    if self._media_ready(page, mid):
                                        _status(page, "✓ Đã có kết quả, đang tải về…")
                                        self._click_download(page, s, mid, job["kind"], job.get("videoQuality") or DEFAULT_VIDEO_QUALITY)
                                        break
                                except Exception:
                                    pass

                    ctx.pages[0].wait_for_timeout(1_000)  # để Playwright xử lý sự kiện download / nút bấm trong lúc chờ
            except PlaywrightError as e:
                # chỉ khi cửa sổ thật sự đóng mới tạm dừng phim (cảnh này quay về "Chờ chạy"); lỗi khác là lỗi tạm thời
                if page.is_closed() or not ctx.pages:
                    raise TabLost(f"Bạn đã đóng cửa sổ Flow ở cảnh {job['externalId']}") from e
                raise TransientError(f"Lỗi trình duyệt khi chờ tải về: {str(e)[:300]}") from e
            self._clear_pending(job)
            try:
                ctx.remove_listener("download", on_download)
            except Exception:
                pass
            if hasattr(ctx, "_afm_handlers") and user_action.append in ctx._afm_handlers:
                ctx._afm_handlers.remove(user_action.append)
            return GenerationResult(staged_file=captured[-1], remote_ref=project_url(page.url) if not page.is_closed() else None)

    @staticmethod
    def _ensure_project(page, job: dict, strict: bool = False):
        """
        Flow mở ra màn hình chính (danh sách dự án): chưa có ô prompt. Phim đã có "Link dự án trên Flow" thì vào thẳng
        dự án đó; chưa có thì bấm "Dự án mới" / "New project". Dự án tạo ra được backend nhớ lại cho các cảnh sau.
        strict (chạy ngầm): nhận ra tài khoản CHƯA ĐĂNG NHẬP thì ném AuthRequired để backend đưa tài khoản về "Cần đăng nhập".
        """
        try:
            FlowBrowserDriver._enter_project(page, strict)
        except AuthRequired as e:
            raise AuthRequired(f"{job['account'].get('email')}: {e}") from e
        finally:
            url = page.url if not page.is_closed() else ""
            if "/project/" in url and project_url(url) != project_url(job.get("flowProjectUrl")) and callable(job.get("_on_project")):
                job["_on_project"](project_url(url))

    @staticmethod
    def _enter_project(page, strict: bool = False):
        if "/project/" in page.url:
            # link đã nhớ có thể trỏ tới dự án đã bị xóa: Flow chuyển sang /404?reason=project sau khi tải trang
            # chờ tới khi ô prompt hiện (dự án còn: vào thẳng) hoặc Flow chuyển sang /404 (dự án đã xóa); tối đa 6s
            deadline = time.monotonic() + 6
            while "/404" not in page.url:
                try:
                    if page.locator("[role=textbox][contenteditable=true]").first.is_visible():
                        return
                except PlaywrightError:
                    pass
                if time.monotonic() > deadline:
                    return
                page.wait_for_timeout(150)
        if "/404" in page.url:
            log.info("Dự án Flow đã lưu không còn (%s): tạo dự án mới", page.url)
            page.goto(FLOW_URL, wait_until="domcontentloaded")
            page.wait_for_timeout(3_000)
        try:
            target = FlowBrowserDriver._new_project_button(page)
            if target is not None:
                target.first.click()
                page.wait_for_url(re.compile(r"/project/"), timeout=20_000)
                page.wait_for_timeout(3_000)
                log.info("Đã bấm 'Dự án mới' trên Flow → %s", page.url)
            elif strict and FlowBrowserDriver._logged_out(page):
                # chạy ngầm: không ai ngồi xem để đăng nhập hộ. (Chế độ "Mở Flow" thì để người dùng tự đăng nhập trong cửa sổ.)
                raise AuthRequired("chưa đăng nhập Google Flow hoặc phiên đã hết hạn")
        except PlaywrightError as e:
            log.warning("Không tự bấm 'Dự án mới' được, người dùng làm tay: %s", str(e)[:200])

    @staticmethod
    def _new_project_button(page, timeout_s: float = 15.0):
        """
        Nút "Dự án mới / New project" ở trang chủ Flow. PHẢI CHỜ nó xuất hiện: ngay sau khi tải trang chưa có (đã đo: hiện sau
        khoảng 0,5s); đếm ngay thì tưởng không có và job báo "Chưa vào được dự án" trong chưa đầy 1 giây (lỗi đã gặp: mọi lần
        chạy đầu tiên của phim chưa có link dự án Flow đều thất bại, 3 lần liền thì tài khoản bị cho nghỉ).
        """
        name = re.compile(r"Dự án mới|New project", re.I)
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            for loc in (page.get_by_role("button", name=name), page.get_by_text(name)):
                if loc.count() > 0:
                    return loc
            if "accounts.google.com" in page.url:
                return None   # Flow đã chuyển sang trang đăng nhập Google
            page.wait_for_timeout(150)
        return None

    @staticmethod
    def _logged_out(page) -> bool:
        """
        Trang Flow đang ở trạng thái CHƯA đăng nhập. Đã đo: tài khoản đăng nhập có huy hiệu aria-label "Google Account: … (email)"
        ngay sau khi tải; chưa đăng nhập thì Flow đưa về trang giới thiệu (…/about) hoặc trang đăng nhập Google, không có huy hiệu.
        Trang gần như trống (chưa tải xong / mất mạng) thì KHÔNG kết luận (tránh báo "cần đăng nhập" oan).
        """
        if "accounts.google.com" in page.url or "/about" in page.url.split("flow.google.com", 1)[-1][:12]:
            return True
        try:
            return bool(page.evaluate("""() => {
                if ((document.body.innerText || '').trim().length < 40) return false;
                const badge = [...document.querySelectorAll('[aria-label]')].some(e => {
                    const a = e.getAttribute('aria-label') || ''; return /@/.test(a) && /google/i.test(a); });
                return !badge; }"""))
        except PlaywrightError:
            return False

    def _prefill_best_effort(self, page, s: dict, job: dict, should_stop=None) -> bool:
        """Tự upload ảnh còn thiếu + gõ prompt có chip @tag (không bấm Tạo). True nếu gắn được đủ ảnh."""
        if "/project" not in page.url:
            # chưa vào dự án: ô tìm kiếm ở màn hình chính có thể khớp selector prompt_box, đừng điền nhầm
            log.info("FOREGROUND: chưa vào dự án Flow, bỏ qua bước tự điền")
            return False
        try:
            self._wait_prompt_box(page, s)
        except TransientError:
            return False
        page.set_default_timeout(10_000)   # bước "cố gắng": không treo 45s ở một nút không có
        try:
            return self._prefill_steps(page, s, job, should_stop)
        finally:
            if not page.is_closed():
                page.set_default_timeout(45_000)

    def _prefill_steps(self, page, s: dict, job: dict, should_stop=None) -> bool:
        uploaded = True
        for name, step in [("đặt cài đặt (x1, độ dài, độ phân giải)", lambda: self._apply_settings(page, s, job["kind"], job)),
                           ("chọn chế độ", lambda: self._select_mode(page, s, job["kind"])),
                           ("upload ảnh reference", lambda: self._upload_refs(page, s, job["referenceImages"], should_stop))]:
            _status(page, "Đang " + name + "…")
            try:
                step()
            except (PlaywrightError, AssertionError, Rejected, TransientError) as e:
                _status(page, "Không tự " + name + " được: " + str(e)[:120])
                log.warning("FOREGROUND: không tự %s được, người dùng làm tay: %s", name, str(e)[:200])
                if name == "upload ảnh reference":
                    uploaded = False
        ids = self._ref_ids(job)
        _status(page, "Đang gõ prompt và gắn @tag…")
        try:
            attached = self._compose_prompt(page, s, job["prompt"], ids if uploaded else [], mention_all=job["kind"] == "IMAGE")
        except PlaywrightError as e:
            log.warning("FOREGROUND: không tự điền prompt được: %s", str(e)[:200])
            _status(page, "Không tự gõ được prompt: dùng nút Copy prompt")
            return False
        need = self._needed_refs(self._shown_prompt(job), ids)
        ok = uploaded and need <= attached
        _status(page, ("✓ Đã điền xong prompt" + (f" + {len(attached)} ảnh @tag" if attached else "") + ". Kiểm tra rồi bấm Tạo.")
                if ok else f"Chỉ gắn được {len(attached & need)}/{len(need)} ảnh: gắn tay ảnh còn thiếu (thư mục ảnh đã mở)")
        return ok
