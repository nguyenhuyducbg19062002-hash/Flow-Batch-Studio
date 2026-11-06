"""
Nhịp thao tác "như người thật" trên Google Flow: mỗi bước dừng một khoảng NGẪU NHIÊN, không đều nhau.

- Prompt: dán thẳng cả đoạn (như Ctrl+V), không gõ từng chữ.
- Gắn ảnh @tag: chậm rãi: dừng trước khi gõ "@", gõ tên ảnh từng ký tự, nhìn danh sách gợi ý rồi mới bấm chọn.
- Các thao tác khác (bấm ô prompt, mở bảng ảnh, chỉnh cài đặt, bấm Tạo, chuyển sang mục kế tiếp): từ tốn, vừa phải.

Hệ số tốc độ: biến môi trường AFM_HUMAN_PACE (mặc định 1). 0 = không dừng (chạy thử / test), 0.5 = nhanh gấp đôi,
2 = chậm gấp đôi. Backend truyền giá trị trong Cài đặt ("human_pace") xuống worker qua payload, ưu tiên hơn biến môi trường.
"""
from __future__ import annotations

import os
import random
import time

_DEFAULT = os.environ.get("AFM_HUMAN_PACE", "1")


def factor(job: dict | None = None) -> float:
    raw = (job or {}).get("humanPace")
    if raw is None:
        raw = _DEFAULT
    try:
        return max(0.0, float(raw))
    except (TypeError, ValueError):
        return 1.0


class Pace:
    """Dừng ngẫu nhiên trong [lo, hi] giây, nhân với hệ số tốc độ. Dùng page.wait_for_timeout để Playwright vẫn xử lý sự kiện."""

    def __init__(self, f: float = 1.0):
        self.f = f

    def pause(self, page, lo: float, hi: float) -> None:
        if self.f <= 0:
            return
        ms = int(random.uniform(lo, hi) * self.f * 1000)
        if ms <= 0:
            return
        try:
            page.wait_for_timeout(ms)
        except Exception:
            time.sleep(ms / 1000)

    def type_slow(self, page, text: str, lo: float = 0.07, hi: float = 0.19) -> None:
        """Gõ từng ký tự, mỗi ký tự cách nhau ngẫu nhiên (gõ tên ảnh sau @)."""
        if self.f <= 0:
            page.keyboard.type(text, delay=5)
            return
        for ch in text:
            page.keyboard.type(ch)
            self.pause(page, lo, hi)
            if random.random() < 0.08:   # thỉnh thoảng ngập ngừng lâu hơn một chút
                self.pause(page, 0.2, 0.5)

    def click_delay(self) -> float:
        """Thời gian dừng chuột trên nút trước khi bấm (giây)."""
        return random.uniform(0.08, 0.25) * self.f if self.f > 0 else 0.02


NONE = Pace(0)
