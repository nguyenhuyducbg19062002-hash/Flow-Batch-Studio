import unittest
from pathlib import Path
from unittest import mock

from drivers import stealth


class StealthTests(unittest.TestCase):

    def test_extension_path_exists(self):
        ext_path = stealth.get_extension_path()
        self.assertIsNotNone(ext_path)
        self.assertTrue((ext_path / "manifest.json").is_file())
        self.assertTrue((ext_path / "content_main.js").is_file())
        self.assertTrue((ext_path / "content_isolated.js").is_file())

    def test_ignore_default_args(self):
        ignored = stealth.get_ignore_default_args()
        self.assertIn("--enable-automation", ignored)

    def test_stealth_args(self):
        args = stealth.get_stealth_args()
        self.assertTrue(any("--disable-blink-features=AutomationControlled" in a for a in args))
        self.assertTrue(any("--load-extension" in a for a in args))
        self.assertTrue(any("--disable-extensions-except" in a for a in args))

    def test_channel_override_and_fallback(self):
        with mock.patch.dict("os.environ", {"AVS_BROWSER_CHANNEL": "msedge"}):
            self.assertEqual(stealth.browser_channel(), "msedge")
        with mock.patch.dict("os.environ", {"AVS_BROWSER_CHANNEL": ""}):
            with mock.patch.object(stealth, "_chrome_installed", return_value=True):
                self.assertEqual(stealth.browser_channel(), "chrome")
            with mock.patch.object(stealth, "_chrome_installed", return_value=False):
                self.assertEqual(stealth.browser_channel(), "msedge")

    def test_launch_chrome_loads_extension_via_cdp(self):
        pw = mock.MagicMock()
        ctx = pw.chromium.launch_persistent_context.return_value
        with mock.patch.object(stealth, "browser_channel", return_value="chrome"):
            stealth.launch_profile(pw, "C:/p", headless=True, extra_args=["--x"])
        kw = pw.chromium.launch_persistent_context.call_args.kwargs
        self.assertEqual(kw["channel"], "chrome")
        self.assertIn("--enable-unsafe-extension-debugging", kw["args"])
        self.assertIn("--x", kw["args"])
        self.assertFalse(any(a.startswith("--load-extension") for a in kw["args"]))
        self.assertIn("--disable-extensions", kw["ignore_default_args"])
        cdp = ctx.browser.new_browser_cdp_session.return_value
        cdp.send.assert_called_once_with("Extensions.loadUnpacked", {"path": str(stealth.get_extension_path())})

    def test_launch_edge_uses_load_extension_flag(self):
        pw = mock.MagicMock()
        ctx = pw.chromium.launch_persistent_context.return_value
        with mock.patch.object(stealth, "browser_channel", return_value="msedge"):
            stealth.launch_profile(pw, "C:/p", headless=False)
        kw = pw.chromium.launch_persistent_context.call_args.kwargs
        self.assertEqual(kw["channel"], "msedge")
        self.assertTrue(any(a.startswith("--load-extension") for a in kw["args"]))
        ctx.browser.new_browser_cdp_session.assert_not_called()

    def test_stealth_init_script_content(self):
        script = stealth.STEALTH_INIT_SCRIPT
        self.assertIn("Navigator.prototype", script)
        self.assertIn("webdriver", script)
        self.assertIn("window.chrome", script)
        self.assertIn("plugins", script)

    def test_human_click_fallback(self):
        class MockLoc:
            def __init__(self):
                self.clicked = False
            def click(self, *a, **k):
                self.clicked = True

        page = mock.MagicMock()
        loc = MockLoc()
        stealth.human_click(page, loc)
        self.assertTrue(loc.clicked)

    def test_human_click_mouse(self):
        page = mock.MagicMock()
        loc = mock.MagicMock()
        loc.bounding_box.return_value = {"x": 100, "y": 200, "width": 50, "height": 30}
        stealth.human_click(page, loc)
        self.assertTrue(page.mouse.move.called)
        self.assertTrue(page.mouse.down.called)
        self.assertTrue(page.mouse.up.called)
        self.assertFalse(loc.click.called)

    def test_human_type(self):
        page = mock.MagicMock()
        stealth.human_type(page, None, "Hello world")
        self.assertTrue(page.keyboard.type.called or page.keyboard.insert_text.called)

    def test_send_prompt(self):
        page = mock.MagicMock()
        btn = mock.MagicMock()
        btn.count.return_value = 1
        page.locator.return_value.first = btn
        res = stealth.send_prompt(page, {"generate_button": "button.gen"}, min_delay=0)
        self.assertTrue(res)
        self.assertTrue(btn.click.called)


if __name__ == "__main__":
    unittest.main()
