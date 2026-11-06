"""
Kiểm tra một tài khoản Google Flow đã đăng nhập chưa: mở Chrome/Edge (Playwright) với ĐÚNG profile của tài khoản, vào Flow, đọc huy hiệu
tài khoản. In ra MỘT dòng JSON cuối cùng trên stdout:

  {"state": "logged_in" | "logged_out" | "in_use" | "error", "email": "...", "message": "..."}

  logged_in   huy hiệu "Google Account: … (email)" có mặt; email là email đọc được trên trang
  logged_out  Flow đưa về trang giới thiệu (/about) hoặc trang đăng nhập Google, không có huy hiệu
  in_use      profile đang được một cửa sổ trình duyệt khác giữ (ví dụ cửa sổ đăng nhập chưa đóng): chưa kết luận được
  error       lỗi khác (mạng, proxy…): chưa kết luận được

Cách dùng: python check_login.py <thư mục profile> [proxy]     (backend gọi, xem LoginChecker.java)
"""
import json
import re
import sys
import time
from pathlib import Path

ENGINE_DIR = Path(__file__).resolve().parent
if str(ENGINE_DIR) not in sys.path:
    sys.path.insert(0, str(ENGINE_DIR))

from playwright.sync_api import Error as PlaywrightError  # noqa: E402
from playwright.sync_api import sync_playwright  # noqa: E402

from drivers.errors import PROXY_ERRORS, proxy_error  # noqa: E402
from drivers.flow_browser import FLOW_URL, parse_proxy
from drivers.stealth import browser_name, detect_and_solve_turnstile, launch_profile  # noqa: E402

BADGE_JS = """() => { for (const e of document.querySelectorAll('[aria-label]')) {
    const a = e.getAttribute('aria-label') || ''; if (/@/.test(a) && /google/i.test(a)) return a; } return null; }"""
EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")


def result(state: str, email: str = "", message: str = "") -> None:
    print(json.dumps({"state": state, "email": email, "message": message}, ensure_ascii=False), flush=True)


def check(profile: str, proxy: str | None, timeout_s: float = 25.0) -> None:
    with sync_playwright() as pw:
        try:
            ctx = launch_profile(pw, profile, headless=True, proxy=parse_proxy(proxy),
                                 extra_args=["--disable-background-mode"])
        except PlaywrightError as e:
            msg = str(e)
            if "ProcessSingleton" in msg or "already in use" in msg.lower() or "Target page, context or browser has been closed" in msg:
                return result("in_use", message=f"Profile đang mở ở một cửa sổ {browser_name()} khác")
            if proxy_error(msg):
                return result("error", message=f"Proxy không dùng được ({proxy_error(msg)})")
            return result("error", message=f"Không mở được {browser_name()}: {msg[:200]}")
        try:
            page = ctx.pages[0] if ctx.pages else ctx.new_page()
            page.set_default_timeout(20_000)
            page.goto(FLOW_URL, wait_until="domcontentloaded")
            detect_and_solve_turnstile(page, timeout_s=3.0)
            deadline = time.monotonic() + timeout_s
            while time.monotonic() < deadline:
                badge = page.evaluate(BADGE_JS)
                if badge:
                    m = EMAIL_RE.search(badge)
                    return result("logged_in", email=m.group(0) if m else "", message="Đã đăng nhập")
                path = page.url.split("flow.google.com", 1)[-1][:12]
                if "accounts.google.com" in page.url or path.startswith("/about"):
                    return result("logged_out", message="Chưa đăng nhập: Flow đưa về trang giới thiệu / đăng nhập Google")
                page.wait_for_timeout(300)
            return result("error", message="Quá thời gian chờ trang Flow tải xong (mạng chậm?)")
        except PlaywrightError as e:
            if proxy_error(str(e)):
                return result("error", message=f"Proxy không kết nối được ({proxy_error(str(e))})")
            return result("error", message=f"Lỗi khi mở Flow: {str(e)[:200]}")
        finally:
            try:
                ctx.close()
            except PlaywrightError:
                pass


if __name__ == "__main__":
    if len(sys.argv) < 2:
        result("error", message="Thiếu thư mục profile")
        sys.exit(2)
    check(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else None)
