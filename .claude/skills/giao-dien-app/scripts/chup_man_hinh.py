"""
Chụp màn hình từng màn của Flow Batch Studio để kiểm tra giao diện trước/sau khi sửa.

    python -I chup_man_hinh.py --url http://127.0.0.1:18080 --out anh/ [--views projects,scenes] [--size 1366x768] [--project 1]

Cần Playwright (đã có trên máy, dùng Edge cài sẵn: channel=msedge). Mỗi màn một file <view>-<rộng>x<cao>.png.
In ra lỗi console của trang (nếu có) để bắt lỗi giao diện.
"""
import argparse
import os
import sys

# console Windows mặc định cp1252: in tiếng Việt sẽ lỗi
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from playwright.sync_api import sync_playwright

VIEWS = {
    "accounts": "Tài khoản",
    "projects": "Phim",
    "images": "Tạo ảnh",
    "videos": "Tạo video",
    "manual": "Tự làm trên Flow",
    "settings": "Cài đặt",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:18080")
    ap.add_argument("--out", required=True)
    ap.add_argument("--views", default=",".join(VIEWS))
    ap.add_argument("--size", default="1366x768")
    ap.add_argument("--project", default=None, help="id phim chọn sẵn")
    ap.add_argument("--full", action="store_true", help="chụp cả trang dài")
    a = ap.parse_args()
    w, h = (int(x) for x in a.size.lower().split("x"))
    os.makedirs(a.out, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge")
        page = browser.new_page(viewport={"width": w, "height": h})
        errors = []
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        page.goto(a.url)
        for view in [v.strip() for v in a.views.split(",") if v.strip()]:
            js = f"localStorage.setItem('afm.view', JSON.stringify('{view}'));"
            if a.project:
                js += f"localStorage.setItem('afm.projectId', '{a.project}');"
            page.evaluate(js)
            page.reload()
            page.wait_for_timeout(2500)
            path = os.path.join(a.out, f"{view}-{w}x{h}.png")
            page.screenshot(path=path, full_page=a.full)
            print("đã chụp", path)
        browser.close()
    if errors:
        print("LỖI CONSOLE:")
        for e in errors:
            print(" -", e)


if __name__ == "__main__":
    main()
