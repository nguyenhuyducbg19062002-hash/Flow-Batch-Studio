"""Chrome / tab Flow bị đóng giữa chừng: dừng phim chờ người dùng (PAUSED), không đốt lượt thử của cảnh."""
import sys
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

from playwright.sync_api import Error as PlaywrightError  # noqa: E402

from drivers import flow_extension_ui as fx  # noqa: E402
from drivers.errors import PausedByUser, TransientError  # noqa: E402
from drivers.flow_browser import FlowBrowserDriver  # noqa: E402


class FakeChromium:
    def __init__(self, errors):
        self.errors = list(errors)
        self.calls = 0

    def connect_over_cdp(self, url, timeout):
        self.calls += 1
        if self.errors:
            raise PlaywrightError(self.errors.pop(0))
        return "browser"


class ConnectTest(unittest.TestCase):
    def pw(self, errors):
        return mock.Mock(chromium=FakeChromium(errors))

    def test_no_window_waits_then_connects(self):
        pw = self.pw(["Protocol error (Target.setAutoAttach): No current window"])
        with mock.patch.object(fx.time, "sleep"):
            self.assertEqual(fx.FlowExtensionUiDriver._connect(pw), "browser")
        self.assertEqual(pw.chromium.calls, 2)

    def test_no_window_too_long_pauses(self):
        pw = self.pw(["No current window"] * 100)
        with mock.patch.object(fx.time, "sleep"), mock.patch.object(fx.FlowExtensionUiDriver, "NO_WINDOW_WAIT_S", -1):
            with self.assertRaises(PausedByUser):
                fx.FlowExtensionUiDriver._connect(pw)

    def test_other_error_is_transient(self):
        with self.assertRaises(TransientError):
            fx.FlowExtensionUiDriver._connect(self.pw(["ECONNREFUSED"]))


class RetryTileClosedTest(unittest.TestCase):
    def test_tab_closed_while_retrying_pauses(self):
        d = FlowBrowserDriver.__new__(FlowBrowserDriver)
        page = mock.Mock()
        page.evaluate.return_value = "tile"
        page.is_closed.return_value = True
        page.locator.return_value.first.scroll_into_view_if_needed.side_effect = PlaywrightError(
            "Locator.scroll_into_view_if_needed: Target page, context or browser has been closed")
        with self.assertRaises(PausedByUser):
            d._retry_error_tile(page, {"result_card": "x"}, Counter())


if __name__ == "__main__":
    unittest.main()
