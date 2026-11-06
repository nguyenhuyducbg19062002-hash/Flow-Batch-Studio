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


class ReloadTest(unittest.TestCase):
    def driver(self, outcomes):
        d = fx.FlowExtensionUiDriver.__new__(fx.FlowExtensionUiDriver)
        d._wait_tab = mock.Mock()
        d._step = mock.Mock()
        seq = list(outcomes)

        def gen(self_, job, should_stop):
            o = seq.pop(0)
            if isinstance(o, Exception):
                raise o
            return o
        return d, gen

    def run_bg(self, outcomes):
        d, gen = self.driver(outcomes)
        with mock.patch.object(fx.FlowBrowserDriver, "generate", gen), mock.patch.object(fx.time, "sleep"):
            return d, d._background({"externalId": "CHAR-1"}, lambda: False)

    def test_tab_closed_reloads_and_retries(self):
        from drivers.errors import TabLost
        d, res = self.run_bg([TabLost("tab đóng"), PlaywrightError("Target page, context or browser has been closed"), "ok"])
        self.assertEqual(res, "ok")
        self.assertEqual(d._wait_tab.call_count, 3)

    def test_gives_up_after_limit(self):
        from drivers.errors import TabLost
        with self.assertRaises(TabLost):
            self.run_bg([TabLost("x")] * 10)

    def test_user_pause_not_reloaded(self):
        with self.assertRaises(PausedByUser):
            self.run_bg([PausedByUser("Bạn bấm Tạm dừng phim"), "ok"])

    def test_other_playwright_error_transient(self):
        with self.assertRaises(TransientError):
            self.run_bg([PlaywrightError("Timeout 3000ms exceeded"), "ok"])
