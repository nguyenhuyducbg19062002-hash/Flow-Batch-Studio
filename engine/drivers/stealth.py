"""
Hệ thống Anti-Detect & Stealth Browser cho Flow Batch Studio.
Kết hợp 2 phương pháp mạnh nhất:
  1. Undetected Browser (Playwright stealth flags, CDP patches, init scripts, human behavior simulation).
  2. Native Browser Extension (Manifest V3, chạy ở world: MAIN tại document_start và world: ISOLATED để tự động
     giải Turnstile / Verify it's you).

Trình duyệt: Google Chrome nếu máy có, không thì Microsoft Edge (đổi bằng AVS_BROWSER_CHANNEL = chrome | msedge | chromium).
Chrome bản thường (từ 137) bỏ qua cờ --load-extension, nên với Chrome extension được nạp qua CDP
Extensions.loadUnpacked (cần cờ --enable-unsafe-extension-debugging) mỗi lần mở; Edge / Chromium vẫn dùng --load-extension.
"""
import logging
import os
import random
import re
import time
from pathlib import Path

from playwright.sync_api import Error as PlaywrightError

log = logging.getLogger("stealth")

# Thư mục chứa Chrome/Edge Extension đi kèm
ENGINE_DIR = Path(__file__).resolve().parent.parent
EXTENSION_DIR = ENGINE_DIR / "extension"

CHANNEL_NAMES = {"chrome": "Chrome", "msedge": "Edge", "chromium": "Chromium"}


def _chrome_installed() -> bool:
    for env in ("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"):
        base = os.environ.get(env)
        if base and (Path(base) / "Google" / "Chrome" / "Application" / "chrome.exe").is_file():
            return True
    return False


def browser_channel() -> str:
    """Kênh Playwright dùng để mở profile tài khoản: Chrome nếu có, không thì Edge."""
    ch = os.environ.get("AVS_BROWSER_CHANNEL", "").strip().lower()
    if ch in CHANNEL_NAMES:
        return ch
    return "chrome" if _chrome_installed() else "msedge"


def browser_name(channel: str | None = None) -> str:
    return CHANNEL_NAMES.get(channel or browser_channel(), "trình duyệt")

STEALTH_INIT_SCRIPT = """
(function () {
  'use strict';

  // 1. Gỡ bỏ thuộc tính navigator.webdriver
  try {
    const navProto = Object.getPrototypeOf(navigator);
    if ('webdriver' in navProto) {
      delete navProto.webdriver;
    }
    Object.defineProperty(Navigator.prototype, 'webdriver', {
      get: () => undefined,
      enumerable: true,
      configurable: true
    });
  } catch (e) {}

  // 2. Tạo window.chrome đầy đủ
  try {
    if (!window.chrome) {
      window.chrome = {};
    }
    if (!window.chrome.runtime) {
      window.chrome.runtime = {
        id: undefined,
        connect: function () {},
        sendMessage: function () {},
        onMessage: { addListener: function () {}, removeListener: function () {} }
      };
    }
    if (!window.chrome.loadTimes) {
      window.chrome.loadTimes = function () {
        const perf = window.performance;
        const timing = perf && perf.timing;
        const navStart = timing ? timing.navigationStart / 1000 : Date.now() / 1000;
        return {
          requestTime: navStart,
          startLoadTime: navStart,
          commitLoadTime: navStart + 0.1,
          finishDocumentLoadTime: navStart + 0.3,
          firstPaintTime: navStart + 0.15,
          finishLoadTime: navStart + 0.4,
          wasFetchedViaSpdy: true,
          wasNpnNegotiated: true,
          npnNegotiatedProtocol: 'h2',
          wasAlternateProtocolAvailable: false,
          connectionInfo: 'h2'
        };
      };
    }
    if (!window.chrome.csi) {
      window.chrome.csi = function () {
        const perf = window.performance;
        return {
          startE: perf && perf.timing ? perf.timing.navigationStart : Date.now(),
          onloadT: perf && perf.timing ? perf.timing.loadEventEnd : Date.now(),
          pageT: perf ? perf.now() : 100,
          tran: 15
        };
      };
    }
    if (!window.chrome.app) {
      window.chrome.app = {
        isInstalled: false,
        InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
        RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
      };
    }
  } catch (e) {}

  // 3. Plugins giả lập
  try {
    if (!navigator.plugins || navigator.plugins.length === 0) {
      const mockPlugins = [
        { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'Microsoft Edge PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'WebKit built-in PDF', filename: 'internal-pdf-viewer', description: 'Portable Document Format' }
      ];
      Object.defineProperty(navigator, 'plugins', {
        get: () => mockPlugins,
        enumerable: true,
        configurable: true
      });
    }
  } catch (e) {}

  // 4. Languages chuẩn
  try {
    if (!navigator.languages || navigator.languages.length === 0) {
      Object.defineProperty(navigator, 'languages', {
        get: () => ['vi-VN', 'vi', 'en-US', 'en'],
        enumerable: true,
        configurable: true
      });
    }
  } catch (e) {}

  // 5. Permissions API
  try {
    if (navigator.permissions && navigator.permissions.query) {
      const origQuery = navigator.permissions.query;
      navigator.permissions.query = function (params) {
        if (params && params.name === 'notifications') {
          const perm = typeof Notification !== 'undefined' ? Notification.permission : 'default';
          return Promise.resolve({
            state: perm === 'default' ? 'prompt' : perm,
            onchange: null
          });
        }
        return origQuery.apply(this, arguments);
      };
    }
  } catch (e) {}

  // 6. Xóa các biến nhận diện tự động hóa
  try {
    delete window.__playwright;
    delete window.__pw_manualStatus;
    delete window.__puppeteer_evaluation_script__;
    delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
    delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;
    delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;
  } catch (e) {}
})();
"""


def get_extension_path() -> Path | None:
    """Trả về thư mục extension nếu tồn tại."""
    if EXTENSION_DIR.is_dir() and (EXTENSION_DIR / "manifest.json").is_file():
        return EXTENSION_DIR.resolve()
    return None


def get_ignore_default_args() -> list[str]:
    """Các cờ mặc định của Playwright cần loại bỏ để tránh lộ automation."""
    return ["--enable-automation"]


def get_stealth_args(extension_path: Path | None = None, load_extension: bool = True) -> list[str]:
    """Cờ dòng lệnh Chromium chống phát hiện bot và (load_extension) nạp extension qua --load-extension."""
    ext = (extension_path or get_extension_path()) if load_extension else None
    args = [
        "--disable-blink-features=AutomationControlled",
        "--disable-infobars",
        "--no-first-run",
        "--no-default-browser-check",
        "--lang=vi-VN,vi,en-US,en",
        "--disable-features=IsolateOrigins,site-per-process",
    ]
    if ext:
        ext_str = str(ext)
        args.append(f"--disable-extensions-except={ext_str}")
        args.append(f"--load-extension={ext_str}")
    return args


def launch_profile(pw, profile_dir: str, *, headless: bool, proxy=None, extra_args=(), **kwargs):
    """
    Mở profile tài khoản bằng Playwright (Chrome nếu có, không thì Edge), nạp extension, áp stealth.
    Lỗi mở trình duyệt (PlaywrightError) được ném nguyên cho nơi gọi phân loại.
    """
    channel = browser_channel()
    ext = get_extension_path()
    via_cdp = channel == "chrome" and ext is not None
    args = list(extra_args) + get_stealth_args(ext, load_extension=not via_cdp)
    ignore = get_ignore_default_args()
    if via_cdp:
        args.append("--enable-unsafe-extension-debugging")
        ignore = ignore + ["--disable-extensions"]
    ctx = pw.chromium.launch_persistent_context(
        user_data_dir=profile_dir,
        channel=channel,
        headless=headless,
        proxy=proxy,
        ignore_default_args=ignore,
        args=args,
        chromium_sandbox=True,   # không thêm cờ --no-sandbox (trình duyệt hiện cảnh báo "unsupported command-line flag")
        **kwargs,
    )
    if via_cdp:
        try:
            cdp = ctx.browser.new_browser_cdp_session()
            cdp.send("Extensions.loadUnpacked", {"path": str(ext)})
        except Exception as e:   # không có extension vẫn chạy được nhờ init script, chỉ kém chống phát hiện hơn
            log.warning("Không nạp được extension vào Chrome qua CDP: %s", e)
    apply_stealth(ctx)
    return ctx


def apply_stealth(context) -> None:
    """Áp dụng script chống phát hiện bot vào context trình duyệt."""
    try:
        context.add_init_script(STEALTH_INIT_SCRIPT)
    except Exception as e:
        log.warning("Không áp dụng được stealth init script: %s", e)


def human_type(page, locator, text: str, min_delay: float = 0.005, max_delay: float = 0.015) -> None:
    """
    Gõ văn bản vào ô nhập. Nếu đoạn văn bản dài (>50 ký tự), tự động chèn nhanh dạng clipboard paste
    (mô phỏng người dùng copy-paste từ kịch bản vào) để điền tức thì, không bắt người dùng chờ.
    Với đoạn ngắn, gõ nhanh có nhịp điệu.
    """
    if not text:
        return
    try:
        if hasattr(page, "keyboard"):
            if len(text) > 50 and hasattr(page.keyboard, "insert_text"):
                page.keyboard.insert_text(text)
                return
            if hasattr(page.keyboard, "type"):
                words = text.split(" ")
                for i, word in enumerate(words):
                    if word:
                        delay_ms = int(random.uniform(min_delay, max_delay) * 1000)
                        page.keyboard.type(word, delay=delay_ms)
                    if i < len(words) - 1:
                        page.keyboard.type(" ", delay=random.randint(10, 25))
                return
    except Exception:
        pass
    if hasattr(page, "keyboard") and hasattr(page.keyboard, "insert_text"):
        page.keyboard.insert_text(text)


def human_click(page, locator, delay_before: float = 0.02) -> None:
    """
    Click mô phỏng người dùng: di chuột nhanh vào nút, dừng ngắn rồi bấm.
    Nếu là mock hoặc không lấy được bounding_box thì gọi locator.click().
    """
    try:
        if hasattr(locator, "bounding_box") and hasattr(page, "mouse"):
            box = locator.bounding_box()
            if box and isinstance(box, dict) and box.get("width", 0) > 0 and box.get("height", 0) > 0:
                # Tọa độ ngẫu nhiên trong khoảng 30% - 70% kích thước phần tử
                x = box["x"] + box["width"] * random.uniform(0.3, 0.7)
                y = box["y"] + box["height"] * random.uniform(0.3, 0.7)
                page.mouse.move(x, y, steps=random.randint(1, 2))
                if delay_before > 0:
                    time.sleep(delay_before)
                page.mouse.down()
                time.sleep(0.02)
                page.mouse.up()
                return
    except Exception:
        pass
    locator.click(timeout=3_000)


def send_prompt(page, selectors: dict, min_delay: float = 0.05) -> bool:
    """
    Tự động gửi prompt vào Google Flow:
    Ưu tiên click nút Bắt đầu tạo (generate_button) với human_click.
    Dự phòng gõ phím Enter trên ô prompt (phím tắt bypass bot) nếu không click được nút.
    """
    time.sleep(min_delay)
    btn_sel = selectors.get("generate_button") or "[aria-label='Bắt đầu tạo'], [aria-label='Start generation']"
    box_sel = selectors.get("prompt_box") or "div[role='textbox'][contenteditable='true']"

    try:
        if hasattr(page, "locator"):
            btn = page.locator(btn_sel).first
            if btn.count() > 0:
                human_click(page, btn)
                return True
    except Exception:
        pass

    # Dự phòng gửi bằng phím Enter trong ô prompt
    try:
        if hasattr(page, "locator") and hasattr(page, "keyboard"):
            box = page.locator(box_sel).first
            if box.count() > 0:
                box.focus()
                page.keyboard.press("Enter")
                return True
    except Exception:
        pass

    try:
        if hasattr(page, "locator"):
            btn = page.locator(btn_sel).first
            human_click(page, btn)
            return True
    except Exception:
        pass
    return False


def detect_and_solve_turnstile(page, timeout_s: float = 4.0) -> bool:
    """
    Quét và tự động giải quyết hộp kiểm tra 'Verify it\'s you' (Cloudflare Turnstile / Challenge).
    Trả về True nếu phát hiện và đã click.
    """
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            # 1. Kiểm tra iframe Turnstile
            for frame in page.frames:
                url = frame.url.lower()
                if "cloudflare" in url or "turnstile" in url or "challenges" in url:
                    cb = frame.locator("input[type=checkbox], .ctp-checkbox-label, #challenge-stage input").first
                    if cb.count() > 0 and cb.is_visible():
                        log.info("Phát hiện hộp kiểm Turnstile trong iframe, đang click giải quyết...")
                        time.sleep(random.uniform(0.5, 1.0))
                        human_click(page, cb)
                        time.sleep(1.5)
                        return True

            # 2. Kiểm tra nút 'Verify it's you' trên trang
            verify_btn = page.locator("button, div[role=button]").filter(
                has_text=re.compile(r"Verify it's you|Verify you are human|Xác minh bạn là con người", re.I)
            ).first
            if verify_btn.count() > 0 and verify_btn.is_visible():
                log.info("Phát hiện nút 'Verify it\'s you' trên trang, đang click...")
                time.sleep(random.uniform(0.5, 1.0))
                human_click(page, verify_btn)
                time.sleep(1.5)
                return True
        except PlaywrightError:
            pass
        page.wait_for_timeout(300)
    return False
