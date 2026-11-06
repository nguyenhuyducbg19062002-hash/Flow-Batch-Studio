"""Bảng cài đặt Flow: chọn đúng chế độ ảnh / video, tỉ lệ khung của phim và x1."""
import re
import sys
import unittest
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

from drivers import flow_browser as fb  # noqa: E402

RADIOS = ["Không bao giờ", "image Hình ảnh", "videocam Video", "crop_16_9 16:9", "crop_9_16 9:16", "1:1", "x1", "x2", "720p info", "8s"]


class FakeRadio:
    def __init__(self, page, names):
        self.page, self.names = page, names
        self.first = self

    def count(self):
        return len(self.names)

    def nth(self, i):
        return FakeRadio(self.page, [self.names[i]])

    def wait_for(self, **k):
        pass

    def click(self, **k):
        self.page.clicked.append(self.names[0])


class FakePage:
    def __init__(self):
        self.clicked = []
        self.keyboard = mock.MagicMock()

    def locator(self, sel):
        loc = mock.MagicMock()
        loc.count.return_value = 1 if sel == "SETTINGS" else 0
        loc.last.inner_text.return_value = "Nano Banana · 9:16 · x2"
        return loc

    def get_by_role(self, role, name=None):
        if name is None:
            return FakeRadio(self, RADIOS)
        return FakeRadio(self, [n for n in RADIOS if name.search(n)])

    def wait_for_timeout(self, ms):
        pass


class ApplySettingsTest(unittest.TestCase):
    S = {"settings_button": "SETTINGS"}

    def test_image_picks_mode_ratio_and_x1(self):
        page = FakePage()
        fb.FlowBrowserDriver._apply_settings(page, self.S, "IMAGE", {"aspectRatio": "16:9", "humanPace": 0})
        self.assertIn("image Hình ảnh", page.clicked)
        self.assertIn("crop_16_9 16:9", page.clicked)
        self.assertNotIn("crop_9_16 9:16", page.clicked)
        self.assertIn("x1", page.clicked)

    def test_video_picks_ratio_quality_duration(self):
        page = FakePage()
        fb.FlowBrowserDriver._apply_settings(page, self.S, "VIDEO", {"aspectRatio": "9:16", "videoQuality": "720p",
                                                                     "durationSeconds": 8, "humanPace": 0})
        for want in ["videocam Video", "crop_9_16 9:16", "720p info", "8s", "x1"]:
            self.assertIn(want, page.clicked)

    def test_chip_with_other_ratio_does_not_match(self):
        btn = mock.MagicMock()
        btn.last.inner_text.return_value = "Video · 720p · 9:16 · x1"
        self.assertFalse(fb.FlowBrowserDriver._chip_matches(btn, {"aspectRatio": "16:9", "videoQuality": "720p"}))
        self.assertTrue(fb.FlowBrowserDriver._chip_matches(btn, {"aspectRatio": "9:16", "videoQuality": "720p"}))


if __name__ == "__main__":
    unittest.main()
