"""
Google Flow qua Extension, điều khiển GIAO DIỆN tab Flow trong Chrome của người dùng (không gọi API ngầm).

Playwright nối tới bridge (flow_ext/bridge.py, cổng CDP) như tới một Chrome thật; extension chạy các lệnh trên tab
flow.google.com bằng chrome.debugger. Nhờ vậy dùng lại nguyên các bước đã chạy thật của FlowBrowserDriver: vào dự án,
cài đặt (x1, độ dài, độ phân giải), upload ảnh tham chiếu, gõ prompt có chip @tag, bấm Tạo, chờ ô kết quả.

  BACKGROUND (Chạy ngầm): tự làm hết rồi bấm Tải xuống; file về thư mục Downloads của Chrome, extension báo đường dẫn,
                          app chép vào staging.
  FOREGROUND (Mở Flow):   tự upload ảnh + gõ prompt có @tag, KHÔNG bấm Tạo. Người dùng kiểm tra, bấm Tạo; có kết quả thì
                          app tự bấm Tải xuống (hoặc người dùng tự tải), file tải về được nhận như trên.

Mỗi lúc chỉ một cảnh điều khiển tab Flow của một extension (bridge chỉ nhận một Playwright mỗi extension); cảnh khác chờ tới lượt.

Nhiều tài khoản: mỗi tài khoản Google một hồ sơ Chrome có cài extension; extension tự báo email, app tự thêm tài khoản theo
email (backend FlowExtensionAccount.sync). Job của tài khoản nào thì điều khiển đúng Chrome của tài khoản đó (khóa = email,
bridge.ext_key) và chỉ nhận file do extension đó tải. Tài khoản chung "Chrome (Flow Extension)" dùng extension nào cũng được.
"""
from __future__ import annotations

import logging
import os
import shutil
import time
from contextlib import contextmanager
from pathlib import Path

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import TimeoutError as PlaywrightTimeout
from playwright.sync_api import sync_playwright

from flow_ext import bridge

from .errors import Cancelled, ConfigError, GenerationResult, PausedByUser, TabLost, TransientError
from .flow_browser import (DEFAULT_VIDEO_QUALITY, FLOW_URL, FlowBrowserDriver, _status, edit_media_id,
                           project_url)
from .helper_overlay import overlay_script

log = logging.getLogger("flow-ext-ui")

ENGINE_DIR = Path(__file__).resolve().parent.parent
VIDEO_EXT = (".mp4", ".mov", ".webm")
IMAGE_EXT = (".png", ".jpg", ".jpeg", ".webp")
EXTENSION_WAIT_S = int(os.environ.get("AVS_FLOW_EXTENSION_WAIT_S", "180"))
TAB_BUSY_WAIT_S = int(os.environ.get("AVS_FLOW_TAB_BUSY_WAIT_S", "1800"))
DOWNLOAD_WAIT_S = 180


class FlowExtensionUiDriver(FlowBrowserDriver):
    _job: dict | None = None   # cảnh đang chạy (để báo tiến độ)
    _ext: str = ""             # email tài khoản của cảnh đang chạy = khóa chọn extension ("" = extension nào cũng được)

    def __init__(self, selectors_file: Path | None = None):
        super().__init__(selectors_file or ENGINE_DIR / "selectors.json")

    def _use_job(self, job: dict) -> None:
        self._job = job
        self._ext = bridge.ext_key(job.get("account"))

    # ── nối tab Flow qua extension ──────────────────────────────────────────

    def _bridge_status(self) -> dict:
        try:
            return bridge.status(self._ext)
        except Exception:
            return {}

    def _wait_tab(self, should_stop):
        """Chờ extension kết nối và tab Flow rảnh (cảnh khác đang điều khiển thì chờ tới lượt)."""
        try:
            bridge.ensure_bridge()
        except RuntimeError as e:
            raise ConfigError(str(e)) from e
        started = time.monotonic()
        logged = set()
        while True:
            if should_stop():
                raise Cancelled("Người dùng hủy job")
            st = self._bridge_status()
            waited = time.monotonic() - started
            if not st.get("connected"):
                if waited > EXTENSION_WAIT_S:
                    if self._ext:
                        raise TransientError(f"Chrome của {self._ext} chưa nối app: mở hồ sơ Chrome của tài khoản này "
                                             "(có cài extension Flow Bridge) và một tab flow.google.com đã đăng nhập")
                    raise TransientError("Chưa có extension Flow Bridge nào kết nối: mở Chrome có cài extension "
                                         "và một tab flow.google.com đã đăng nhập")
                if "ext" not in logged:
                    logged.add("ext")
                    log.info("Chờ extension Flow Bridge %s kết nối…", self._ext or "")
            elif st.get("cdpBusy"):
                if waited > TAB_BUSY_WAIT_S:
                    raise TransientError("Tab Flow vẫn đang bận với cảnh khác")
                if "busy" not in logged:
                    logged.add("busy")
                    log.info("Tab Flow %s đang chạy cảnh khác: chờ tới lượt", self._ext or "")
            else:
                return
            time.sleep(2)

    @contextmanager
    def _browser(self, account: dict, headless: bool = False, keep_alive: bool = True):
        """Context Playwright trỏ vào tab Flow của người dùng (không mở trình duyệt mới, không đóng tab khi xong)."""
        pw = sync_playwright().start()
        try:
            browser = self._connect(pw, bridge.ext_key(account))
            try:
                ctx = browser.contexts[0] if browser.contexts else None
                if ctx is None or not ctx.pages:
                    raise TransientError("Extension không đưa được tab Flow cho app điều khiển")
                yield ctx
            finally:
                try:
                    browser.close()   # chỉ ngắt kết nối: bridge không chuyển Browser.close cho Chrome
                except Exception:
                    pass
        finally:
            pw.stop()

    # Chrome không còn cửa sổ nào / không có tab Flow: chờ người dùng mở lại một lúc rồi mới dừng phim (không đốt lượt thử)
    NO_WINDOW_WAIT_S = int(os.environ.get("AVS_FLOW_NO_WINDOW_WAIT_S", "45"))
    _NO_WINDOW_MARKS = ("no current window", "no_flow_tab", "no tab with id", "target closed", "target_closed",
                        "has been closed")

    @classmethod
    def _connect(cls, pw, ext: str = ""):
        started = time.monotonic()
        logged = False
        while True:
            try:
                return pw.chromium.connect_over_cdp(bridge.cdp_url(ext), timeout=60_000)
            except PlaywrightError as e:
                msg = str(e)
                if not any(m in msg.lower() for m in cls._NO_WINDOW_MARKS):
                    raise TransientError(f"Không điều khiển được tab Flow qua extension: {msg[:300]}") from e
                if time.monotonic() - started > cls.NO_WINDOW_WAIT_S:
                    raise TabLost("Chrome không còn cửa sổ / tab Flow (đã bị đóng?): mở Chrome, mở tab "
                                  "flow.google.com đã đăng nhập rồi bấm Chạy") from e
                if not logged:
                    logged = True
                    log.warning("Chrome không còn cửa sổ / tab Flow: chờ mở lại (%ss)…", cls.NO_WINDOW_WAIT_S)
                time.sleep(5)

    def _open_flow(self, ctx, job: dict):
        page = ctx.pages[0]
        page.set_default_timeout(45_000)
        target = project_url(job.get("flowProjectUrl")) or FLOW_URL
        if not (target and target in page.url and "/project/" in page.url and not edit_media_id(page.url)):
            try:
                page.goto(target, wait_until="domcontentloaded")
            except PlaywrightTimeout:
                pass
            except PlaywrightError as e:
                raise TransientError(f"Không mở được trang Flow: {str(e)[:300]}") from e
        if "accounts.google.com" in page.url:
            raise TransientError("Tab Flow chưa đăng nhập Google: đăng nhập trong Chrome rồi chạy lại")
        if job.get("runMode") != "FOREGROUND":   # Mở Flow: người dùng thao tác, giữ nguyên cửa sổ của họ
            self._wide_viewport(page)
        return page

    # Cửa sổ Chrome hẹp (vd. hai hồ sơ xếp cạnh nhau, mỗi cửa sổ nửa màn hình): Flow đổi sang bố cục thu gọn, nút "Tải nội dung
    # nghe nhìn lên" / menu ⋮ của ô bị ẩn, app chờ 45s rồi báo lỗi (đã gặp 07/11/2026). Chạy ngầm: cho tab Flow vẽ như màn hình
    # rộng trong lúc app điều khiển (Emulation.setDeviceMetricsOverride); app trả tab (gỡ chrome.debugger) thì Chrome tự bỏ.
    VIEWPORT_MIN_W = int(os.environ.get("AVS_FLOW_VIEWPORT_MIN_W", "1280"))
    VIEWPORT_W = int(os.environ.get("AVS_FLOW_VIEWPORT_W", "1600"))
    VIEWPORT_H = int(os.environ.get("AVS_FLOW_VIEWPORT_H", "900"))

    def _wide_viewport(self, page) -> None:
        try:
            w, h = page.evaluate("() => [window.innerWidth, window.innerHeight]")
        except (PlaywrightError, TypeError, ValueError):
            return
        if w >= self.VIEWPORT_MIN_W and h >= 600:
            return
        size = {"width": max(w, self.VIEWPORT_W), "height": max(h, self.VIEWPORT_H)}
        try:
            page.set_viewport_size(size)
            log.info("Cửa sổ Chrome hẹp (%sx%s): cho tab Flow hiển thị %sx%s trong lúc app điều khiển", w, h,
                     size["width"], size["height"])
        except PlaywrightError as e:
            log.warning("Không đặt được kích thước hiển thị cho tab Flow: %s", str(e)[:200])

    # ── tải file: Chrome tải vào thư mục Downloads, extension báo đường dẫn ──

    @staticmethod
    def _take_download(since: float, kind: str, staging: Path, stem: str, ext: str = "") -> Path | None:
        """File Chrome (của tài khoản ext) tải xong sau thời điểm since, đúng loại (video / ảnh): chép vào staging.
        None = chưa có."""
        want = VIDEO_EXT if kind == "VIDEO" else IMAGE_EXT
        try:
            items = bridge.downloads(since, ext)
        except Exception as e:
            log.debug("Chưa đọc được danh sách file tải: %s", e)
            return None
        for d in reversed(items):
            src = Path(d.get("filename") or "")
            if src.suffix.lower() not in want:
                if src.suffix:
                    log.warning("Bỏ qua file %s: cảnh cần %s", src.name, "video" if kind == "VIDEO" else "ảnh")
                continue
            if not src.is_file() or src.stat().st_size == 0:
                continue
            target = staging / f"{stem}{src.suffix.lower()}"
            part = target.with_name(target.name + ".part")
            shutil.copyfile(src, part)
            os.replace(part, target)
            log.info("Nhận file tải về %s -> %s", src, target)
            return target
        return None

    # ── báo từng bước lên bảng quản lý của extension ───────────────────────

    def _step(self, text: str):
        bridge.progress("step", self._job, text)

    def _apply_settings(self, page, s, kind, job=None):
        self._step("Chỉnh cài đặt Flow (x1, độ dài, độ phân giải)")
        return FlowBrowserDriver._apply_settings(page, s, kind, job)

    def _upload_refs(self, page, s, images, should_stop=None):
        self._step(f"Upload ảnh tham chiếu ({len(images)} ảnh, ảnh đã có thì bỏ qua)")
        return super()._upload_refs(page, s, images, should_stop)

    def _compose_prompt(self, page, s, prompt, ref_ids, mention_all=False):
        self._step("Gõ prompt và gắn @tag")
        return super()._compose_prompt(page, s, prompt, ref_ids, mention_all)

    def _wait_result(self, page, s, before, errors, should_stop, timeout_s, kind=None):
        self._step("Đã bấm Tạo, chờ Flow tạo xong")
        return super()._wait_result(page, s, before, errors, should_stop, timeout_s, kind)

    def _run(self, job: dict, fn):
        """Chạy một cảnh, báo bắt đầu / xong / lỗi lên bảng quản lý."""
        self._use_job(job)
        bridge.progress("start", job, "Chế độ " + ("Mở Flow: điền sẵn" if job.get("runMode") == "FOREGROUND" else "Chạy ngầm"))
        try:
            res = fn()
        except Exception as e:
            bridge.progress("fail", job, str(e)[:300])
            raise
        bridge.progress("done", job, f"Đã lưu {res.staged_file.name}")
        return res

    def _download(self, page, s: dict, media_id: str, staging: Path, stem: str, kind: str, quality: str | None,
                  should_stop=None) -> Path:
        self._step("Tải kết quả về")
        since = time.time() - 1
        self._click_download(page, s, media_id, kind, quality)
        deadline = time.monotonic() + DOWNLOAD_WAIT_S
        while True:
            got = self._take_download(since, kind, staging, stem, self._ext)
            if got:
                return got
            if should_stop and should_stop():
                raise Cancelled("Người dùng hủy job")
            if time.monotonic() > deadline:
                raise TransientError(f"Quá {DOWNLOAD_WAIT_S}s Chrome chưa tải xong file từ Flow")
            page.wait_for_timeout(1_000)

    # ── BACKGROUND ──────────────────────────────────────────────────────────

    def generate(self, job: dict, should_stop) -> GenerationResult:
        return self._run(job, lambda: self._background(job, should_stop))

    # Tab Flow bị đóng / mất kết nối giữa chừng: tự tải lại (extension mở lại tab Flow) và làm lại cảnh này, tối đa ngần này lần
    TAB_RELOADS = int(os.environ.get("AVS_FLOW_TAB_RELOADS", "3"))
    _GONE_MARKS = ("has been closed", "target closed", "target_closed", "no current window", "no tab with id",
                   "websocket", "connection closed", "detached")

    def _background(self, job: dict, should_stop) -> GenerationResult:
        reloads = 0
        while True:
            self._wait_tab(should_stop)
            try:
                return super().generate(job, should_stop)
            except (TabLost, PlaywrightError) as e:
                gone = isinstance(e, TabLost) or any(m in str(e).lower() for m in self._GONE_MARKS)
                if not gone:
                    raise TransientError(f"Mất kết nối tới tab Flow: {str(e)[:300]}") from e
                if reloads >= self.TAB_RELOADS:
                    if isinstance(e, TabLost):
                        raise
                    raise TabLost(f"Mất tab Flow ở cảnh {job['externalId']} sau {reloads} lần tải lại: "
                                  f"{str(e)[:200]}") from e
                reloads += 1
                log.warning("Mất tab Flow ở cảnh %s (%s): tải lại tab Flow và làm lại (%d/%d)",
                            job["externalId"], str(e)[:120], reloads, self.TAB_RELOADS)
                self._step(f"Mất tab Flow: tải lại và làm lại ({reloads}/{self.TAB_RELOADS})")
                if should_stop():
                    raise Cancelled("Người dùng hủy job") from e
                time.sleep(4 + 3 * reloads)

    def run_session(self, first_job: dict, claim_more, on_start, on_done, on_fail, stop_of) -> None:
        """
        Gửi liên tiếp nhiều prompt trong cùng tab Flow (tài khoản đặt "Số lệnh chạy cùng lúc" từ 2), ô nào xong trước thì tải
        về trước (docs/SONG-SONG.md). Dùng chung SessionRunner với chế độ trình duyệt riêng; tải về qua Chrome như thường.
        """
        self._use_job(first_job)
        self._wait_tab(stop_of(first_job))
        bridge.progress("start", first_job, "Gửi liên tiếp tối đa %s prompt" % (first_job.get("account") or {}).get("maxConcurrent"))
        try:
            super().run_session(first_job, claim_more, on_start, on_done, on_fail, stop_of)
        except PlaywrightError as e:
            raise TabLost(f"Mất tab Flow trong phiên gửi liên tiếp: {str(e)[:200]}") from e

    # ── FOREGROUND: điền sẵn, người dùng bấm Tạo ───────────────────────────

    def generate_foreground(self, job: dict, should_stop) -> GenerationResult:
        return self._run(job, lambda: self._foreground(job, should_stop))

    def _foreground(self, job: dict, should_stop) -> GenerationResult:
        s = self._sel([])
        staging = Path(job["stagingDir"])
        staging.mkdir(parents=True, exist_ok=True)
        self._wait_tab(should_stop)
        kind = job["kind"]
        quality = job.get("videoQuality") or DEFAULT_VIDEO_QUALITY
        what = "video" if kind == "VIDEO" else "ảnh"
        since = time.time()
        try:
            with self._browser(job["account"]) as ctx:
                page = self._open_flow(ctx, job)
                script = overlay_script(job, None)
                try:
                    ctx.add_init_script(script)   # hộp hướng dẫn hiện lại nếu trang tải lại
                    page.evaluate(script)
                except PlaywrightError:
                    pass
                self._ensure_project(page, job)
                if "/project/" in page.url:
                    _status(page, "App đang tự điền, đừng thao tác trên tab này…")
                    filled = self._prefill_best_effort(page, s, job, should_stop)
                    _status(page, f"✓ Đã điền xong prompt và gắn @tag. Kiểm tra rồi bấm Tạo; có {what} app tự tải về."
                            if filled else "Chưa tự điền đủ: bạn hoàn thiện prompt / ảnh rồi bấm Tạo.")
                log.info("FOREGROUND %s: đã điền sẵn, chờ người dùng bấm Tạo", job["externalId"])
                self._step("Đã điền sẵn: chờ bạn kiểm tra và bấm Tạo")
                before = set(self._media_ids(page, s) or [])
                errors = self._error_counts(page, s)
                clicked: set[str] = set()
                last_check = 0.0
                while True:
                    if should_stop():
                        raise Cancelled("Người dùng hủy job")
                    got = self._take_download(since, kind, staging, job["externalId"], self._ext)
                    if got:
                        _status(page, f"✓ Đã nhận {what}, app lưu vào phim.")
                        return GenerationResult(staged_file=got, remote_ref=project_url(page.url))
                    if page.is_closed():
                        raise PausedByUser(f"Bạn đã đóng tab Flow ở cảnh {job['externalId']}")
                    if time.monotonic() - last_check > 5:
                        last_check = time.monotonic()
                        self._raise_if_error(page, s, errors)
                    # có kết quả mới đã sẵn sàng: tự bấm Tải xuống một lần (người dùng tự tải cũng được)
                    new = edit_media_id(page.url)
                    ready = [new] if new and new not in before else [
                        m for m in (self._media_ids(page, s, "IMAGE" if kind == "IMAGE" else None) or [])
                        if m not in before and self._media_ready(page, m)]
                    for mid in ready:
                        if mid in clicked:
                            continue
                        clicked.add(mid)
                        _status(page, f"✓ Có {what}, đang tải về…")
                        self._step(f"Có {what}, đang tải về")
                        try:
                            self._click_download(page, s, mid, kind, quality)
                        except (PlaywrightError, TransientError) as e:
                            log.warning("Chưa tự bấm tải được: %s", e)
                            _status(page, f"Chưa tự tải được: bấm ⋮ → Tải xuống trên {what} vừa tạo")
                        break
                    page.wait_for_timeout(1_500)
        except PlaywrightError as e:
            raise PausedByUser(f"Mất kết nối tới tab Flow ở cảnh {job['externalId']} (tab đóng?): {str(e)[:200]}") from e
