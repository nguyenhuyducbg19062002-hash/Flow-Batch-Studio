import sys
import tempfile
import unicodedata
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

from playwright.sync_api import Error as PlaywrightError  # noqa: E402
from playwright.sync_api import TimeoutError as PlaywrightTimeout  # noqa: E402

from drivers import flow_browser as fb  # noqa: E402
from drivers import helper_overlay  # noqa: E402
from drivers.errors import (AccountBroken, AuthRequired, Cancelled, PausedByUser, QuotaExceeded,  # noqa: E402
                            TransientError)

SELECTORS = ENGINE / "selectors.json"


class Loc:
    def __init__(self, page, sel, count=1):
        self.page, self.sel, self._count = page, sel, count
        self.first = self.last = self

    def count(self):
        return self._count

    def click(self, *a, **k):
        self.page.clicks.append(self.sel)

    def wait_for(self, *a, **k):
        pass

    def hover(self, *a, **k):
        pass

    def filter(self, **k):
        return self

    def all_inner_texts(self):
        return []

    def scroll_into_view_if_needed(self):
        pass

    def locator(self, sel, **k):
        return Loc(self.page, sel, self.page.counts.get(sel, 1))


class FakePage:
    def __init__(self, url="https://flow.google.com/project/" + "a" * 8 + "-" + "b" * 4 + "-" + "c" * 4 + "-" + "d" * 4 + "-" + "e" * 12):
        self.url = url
        self.clicks = []
        self.closed = False
        self.counts = {}
        self.listeners = []

    def is_closed(self):
        return self.closed

    def locator(self, sel, **k):
        return Loc(self, sel, self.counts.get(sel, 1))

    def get_by_text(self, text, exact=False):
        return Loc(self, text, self.counts.get(text, 0))

    def wait_for_timeout(self, ms):
        pass

    def evaluate(self, *a, **k):
        return None

    def set_default_timeout(self, ms):
        pass

    def on(self, name, fn):
        self.listeners.append(fn)

    def remove_listener(self, name, fn):
        self.listeners.remove(fn)

    def goto(self, *a, **k):
        pass


class FakeCtx:
    def __init__(self, page):
        self.pages = [page]
        self.handlers = []

    def on(self, name, fn):
        self.handlers.append(fn)

    def grant_permissions(self, *a, **k):
        pass

    def expose_binding(self, *a, **k):
        pass

    def add_init_script(self, *a, **k):
        pass


class FakeDownload:
    def __init__(self, data=b"x", name="a.mp4", fail=False):
        self.data, self.suggested_filename, self.fail = data, name, fail

    def save_as(self, path):
        if self.fail:
            raise OSError("disk full")
        Path(path).write_bytes(self.data)


def driver():
    return fb.FlowBrowserDriver(SELECTORS)


class ProxyTests(unittest.TestCase):
    def test_url_with_credentials(self):
        self.assertEqual(fb.parse_proxy("http://us%40er:pa:ss@1.2.3.4:8080"),
                         {"server": "http://1.2.3.4:8080", "username": "us@er", "password": "pa:ss"})

    def test_ip_port_user_pass(self):
        self.assertEqual(fb.parse_proxy("1.2.3.4:80:u:p"),
                         {"server": "http://1.2.3.4:80", "username": "u", "password": "p"})

    def test_plain_and_scheme(self):
        self.assertEqual(fb.parse_proxy("1.2.3.4:80"), {"server": "http://1.2.3.4:80"})
        self.assertEqual(fb.parse_proxy("socks5://h:1"), {"server": "socks5://h:1"})
        self.assertIsNone(fb.parse_proxy(" "))

    def test_label_hides_secret(self):
        self.assertEqual(fb.proxy_label("http://u:SECRET@1.2.3.4:8080"), "1.2.3.4:8080")
        self.assertNotIn("SECRET", fb.proxy_label("1.2.3.4:80:u:SECRET"))

    def _launch_error(self, msg):
        pw = mock.MagicMock()
        pw.chromium.launch_persistent_context.side_effect = PlaywrightError(msg)
        cm = mock.MagicMock()
        cm.__enter__.return_value = pw
        acc = {"email": "a@b.c", "profileDir": "x", "proxy": "http://u:SECRET@1.2.3.4:8080"}
        with mock.patch.object(fb, "sync_playwright", return_value=cm):
            with self.assertRaises(Exception) as cx:
                with driver()._browser(acc, True):
                    pass
        return cx.exception

    def test_proxy_error_hides_password(self):
        e = self._launch_error("net::ERR_PROXY_CONNECTION_FAILED")
        self.assertIsInstance(e, AccountBroken)
        self.assertNotIn("SECRET", str(e))
        self.assertIn("1.2.3.4:8080", str(e))

    def test_socks5_with_credentials_is_account_broken(self):
        acc = {"email": "a@b.c", "profileDir": "x", "proxy": "socks5://u:SECRET@1.2.3.4:1080"}
        with self.assertRaises(AccountBroken) as cx:
            with driver()._browser(acc, True):
                pass
        self.assertNotIn("SECRET", str(cx.exception))
        self.assertIn("1.2.3.4:1080", str(cx.exception))

    def test_proxy_word_in_launch_log_is_transient(self):
        e = self._launch_error("Failed: --proxy-server=http://1.2.3.4:8080 crashed")
        self.assertIsInstance(e, TransientError)

    def test_open_flow_proxy_error_hides_password(self):
        page = FakePage("about:blank")
        page.goto = mock.Mock(side_effect=PlaywrightError("net::ERR_TUNNEL_CONNECTION_FAILED"))
        ctx = FakeCtx(page)
        job = {"account": {"email": "a@b.c", "proxy": "9.9.9.9:80:u:SECRET"}}
        with self.assertRaises(AccountBroken) as cx:
            driver()._open_flow(ctx, job)
        self.assertNotIn("SECRET", str(cx.exception))


class ForegroundTests(unittest.TestCase):
    def _run(self, page, wait_effect):
        d = driver()
        ctx = FakeCtx(page)
        page.wait_for_timeout = wait_effect(page, ctx)

        @contextmanager
        def browser(account, headless):
            yield ctx

        d._browser = browser
        d._open_flow = lambda c, j: page
        d._ensure_project = lambda p, j, **k: None
        d._prefill_best_effort = lambda *a, **k: False
        job = {"externalId": "S1", "kind": "VIDEO", "stagingDir": tempfile.mkdtemp(), "account": {"email": "a@b.c"}}
        with mock.patch.object(fb, "open_in_explorer"):
            return d.generate_foreground(job, lambda: False), ctx

    def test_timeout_is_transient_not_paused(self):
        page = FakePage()

        def effect(p, ctx):
            def f(ms):
                raise PlaywrightTimeout("Timeout 30000ms exceeded")
            return f

        with self.assertRaises(TransientError):
            self._run(page, effect)

    def test_closed_window_is_paused(self):
        page = FakePage()

        def effect(p, ctx):
            def f(ms):
                p.closed = True
                ctx.pages.clear()
                raise PlaywrightError("Target closed")
            return f

        with self.assertRaises(PausedByUser):
            self._run(page, effect)

    def test_download_handler_error_stops_waiting(self):
        page = FakePage()

        def effect(p, ctx):
            def f(ms):
                for h in ctx.handlers:
                    h(FakeDownload(fail=True))
            return f

        with self.assertRaises(TransientError) as cx:
            self._run(page, effect)
        self.assertIn("disk full", str(cx.exception))

    def test_download_saved_atomically(self):
        page = FakePage()
        sent = []

        def effect(p, ctx):
            def f(ms):
                if not sent:
                    sent.append(1)
                    for h in ctx.handlers:
                        h(FakeDownload(b"data"))
            return f

        res, _ = self._run(page, effect)
        self.assertEqual(res.staged_file.read_bytes(), b"data")
        self.assertFalse(list(res.staged_file.parent.glob("*.part")))


class ErrorTextTests(unittest.TestCase):
    S = {"quota_texts": ["out of credits"], "policy_texts": ["violates our policies"]}

    def test_old_text_ignored_new_text_raises(self):
        page = FakePage()
        page.counts = {"out of credits": 1}
        base = fb.FlowBrowserDriver._error_counts(page, self.S)
        fb.FlowBrowserDriver._raise_if_error(page, self.S, base)
        page.counts = {"out of credits": 2}
        with self.assertRaises(QuotaExceeded):
            fb.FlowBrowserDriver._raise_if_error(page, self.S, base)

    def test_only_visible_texts_are_counted(self):
        page = FakePage()
        page.get_by_text = lambda text, exact=False: mock.Mock(filter=lambda **k: Loc(page, text, 1 if k.get("visible") else 5))
        self.assertEqual(fb.FlowBrowserDriver._error_counts(page, self.S), {("quota_texts", "out of credits"): 1,
                                                                            ("policy_texts", "violates our policies"): 1})

    def test_new_policy_text_rejected(self):
        page = FakePage()
        base = fb.FlowBrowserDriver._error_counts(page, self.S)
        page.counts = {"violates our policies": 1}
        with self.assertRaises(fb.Rejected):
            fb.FlowBrowserDriver._raise_if_error(page, self.S, base)


class ErrorTileTests(unittest.TestCase):
    """Ô video Flow tạo hỏng (<flow-error-tile>) không có media id: phải báo ngay, không chờ tới hết giờ."""
    S = {"result_card": "flow-tile-container", "policy_texts": ["violate our policies"]}

    def page(self, tiles):
        p = mock.Mock()
        p.evaluate = mock.Mock(return_value=tiles)
        return p

    def test_old_error_tile_ignored(self):
        old = ["Characters interacting | Failed"]
        before = fb.Counter(fb.FlowBrowserDriver._error_tiles(self.page(old), self.S))
        fb.FlowBrowserDriver._raise_if_error_tile(self.page(old), self.S, before)

    def test_new_error_tile_is_transient(self):
        before = fb.Counter(["A | Failed"])
        with self.assertRaises(TransientError) as e:
            fb.FlowBrowserDriver._raise_if_error_tile(self.page(["A | Failed", "Characters arguing over money | Failed"]),
                                                      self.S, before)
        self.assertIn("arguing", str(e.exception))

    def test_new_policy_error_tile_rejected(self):
        with self.assertRaises(fb.Rejected):
            fb.FlowBrowserDriver._raise_if_error_tile(self.page(["X | Failed: might violate our policies"]), self.S, fb.Counter())

    def test_error_tile_js_is_valid_regex(self):
        self.assertIn(r"/^\s*(\S{0,12}\s*)?(failed\b|không thành công)/i", fb.FlowBrowserDriver._ERROR_TILES_JS)


class UploadTests(unittest.TestCase):
    def _upload(self, names_before, names_after, filename, should_stop=None):
        tmp = Path(tempfile.mkdtemp())
        img = tmp / filename
        img.write_bytes(b"x")
        page = FakePage()
        files = []
        state = {"uploaded": False}

        class Chooser:
            value = mock.Mock(set_files=lambda f: (files.extend(f), state.update(uploaded=True)))

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        page.expect_file_chooser = lambda: Chooser()
        page.keyboard = mock.Mock()
        d = driver()
        d._listbox_text = lambda p, s: ""
        d._listbox_names = lambda p, s: {d._nfc(x) for x in (names_after if state["uploaded"] else names_before)}
        s = {"add_reference_button": "add", "ingredient_listbox": "lb", "upload_button": "up"}
        res = d._upload_refs(page, s, [str(img)], should_stop)
        return res, files

    def test_substring_name_does_not_count_as_present(self):
        res, files = self._upload(["CHAR-1.png.bak", "CHAR-1.pngx"], ["CHAR-1.png", "CHAR-1.png.bak"], "CHAR-1.png")
        self.assertTrue(res)
        self.assertEqual(len(files), 1)

    def test_exact_name_skips_upload(self):
        res, files = self._upload(["icon", "CHAR-1.png"], [], "CHAR-1.png")
        self.assertFalse(res)
        self.assertEqual(files, [])

    def test_nfc_normalised(self):
        nfd = unicodedata.normalize("NFD", "Ảnh.png")
        nfc = unicodedata.normalize("NFC", "Ảnh.png")
        res, files = self._upload([nfd], [], nfc)
        self.assertFalse(res)

    def test_should_stop_cancels_upload(self):
        with self.assertRaises(Cancelled):
            self._upload([], [], "a.png", should_stop=lambda: True)


class ChipsTests(unittest.TestCase):
    def _job(self):
        return {"externalId": "S1", "kind": "IMAGE", "prompt": "hi @CHAR-1 and @CHAR-2", "referenceImages": ["x/CHAR-1.png", "x/CHAR-2.png"],
                "stagingDir": tempfile.mkdtemp(), "account": {"email": "a@b.c"}, "timeoutSeconds": 5}

    def _gen(self, attached, should_stop=lambda: False, prompt=None):
        d = driver()
        page = FakePage()

        @contextmanager
        def browser(account, headless):
            yield FakeCtx(page)

        d._browser = browser
        d._open_flow = lambda c, j: page
        d._ensure_project = lambda p, j, **k: None
        d._wait_prompt_box = lambda p, s: None
        d._dismiss_popups = lambda p: False
        d._apply_settings = lambda *a, **k: None
        d._select_mode = lambda *a, **k: None
        d._pending_result = lambda *a, **k: None
        d._upload_refs = lambda *a, **k: False
        d._media_ids = lambda *a, **k: []
        d._compose_prompt = lambda *a, **k: attached
        d._error_counts = lambda *a, **k: {}
        d._wait_result = mock.Mock(side_effect=TransientError("stop here"))
        d._save_pending = lambda *a, **k: None
        with self.assertRaises(Exception) as cx:
            job = self._job()
            if prompt:
                job["prompt"] = prompt
            d.generate(job, should_stop)
        return cx.exception, page

    def test_missing_chip_does_not_click_generate(self):
        e, page = self._gen({"CHAR-1"})
        self.assertIsInstance(e, TransientError)
        self.assertIn("1/2", str(e))
        self.assertEqual(page.clicks, [])

    def test_same_id_twice_needs_one_chip(self):
        # cảnh ảnh: mọi ảnh reference đều phải được @ (CHAR-2 được thêm vào cuối prompt), CHAR-1 nhắc 2 lần vẫn chỉ cần 1 chip
        e, page = self._gen({"CHAR-1", "CHAR-2"}, prompt="@CHAR-1 meets @CHAR-1")
        self.assertEqual(str(e), "stop here")
        self.assertEqual(len(page.clicks), 1)

    def test_all_chips_clicks_generate(self):
        e, page = self._gen({"CHAR-1", "CHAR-2"})
        self.assertEqual(str(e), "stop here")
        self.assertEqual(len(page.clicks), 1)

    def test_should_stop_before_generate(self):
        e, page = self._gen({"CHAR-1", "CHAR-2"}, should_stop=lambda: True)
        self.assertIsInstance(e, Cancelled)
        self.assertEqual(page.clicks, [])

    def test_late_login_detected(self):
        d = driver()
        page = FakePage("https://accounts.google.com/signin")

        @contextmanager
        def browser(account, headless):
            yield FakeCtx(page)

        d._browser = browser
        d._open_flow = lambda c, j: page
        d._ensure_project = lambda p, j, **k: None
        with self.assertRaises(AuthRequired):
            d.generate(self._job(), lambda: False)


class DownloadTests(unittest.TestCase):
    S = {"result_card": "card", "tile_menu_button": "menu", "download_menu_item": "dl",
         "download_quality_option": "[q='{quality}']"}
    SUB = "[role=menuitem]:not([disabled])"

    def _click(self, counts, quality="720p"):
        page = FakePage("https://flow.google.com/project/x")
        page.counts = counts
        d = driver()
        d._card = lambda p, s_, k: Loc(page, "card")
        try:
            d._click_download(page, self.S, "m1", "VIDEO", quality)
        finally:
            self.page = page

    def test_exact_quality_clicked(self):
        self._click({})
        self.assertEqual(self.page.clicks, ["menu", "[q='720p']"])

    def test_falls_back_to_lower_quality(self):
        self._click({"[q='720p']": 0, "[q='480p']": 1, "[q='360p']": 1})
        self.assertEqual(self.page.clicks[-1], "[q='480p']")

    def test_falls_back_to_first_labelled_item_of_submenu(self):
        self._click({f"[q='{q}']": 0 for q in fb.VIDEO_QUALITIES})
        self.assertEqual(self.page.clicks, ["menu", self.SUB])

    def test_empty_menu_raises(self):
        with self.assertRaises(TransientError):
            self._click({**{f"[q='{q}']": 0 for q in fb.VIDEO_QUALITIES}, self.SUB: 0})
        self.assertEqual(self.page.clicks, ["menu"])

    def _download(self, dl, should_stop=None):
        page = FakePage()
        d = driver()
        staging = Path(tempfile.mkdtemp())

        def click(*a, **k):
            if dl is not None:
                for fn in list(page.listeners):
                    fn(dl)

        d._click_download = click
        return d._download(page, {}, "m", staging, "S1", "VIDEO", "720p", should_stop), staging

    def test_saved_via_part_and_replaced(self):
        path, staging = self._download(FakeDownload(b"abc"))
        self.assertEqual(path.read_bytes(), b"abc")
        self.assertEqual([p.name for p in staging.iterdir()], ["S1.mp4"])

    def test_failed_save_leaves_no_part_file(self):
        staging = Path(tempfile.mkdtemp())

        class Partial(FakeDownload):
            def save_as(self, path):
                Path(path).write_bytes(b"half")
                raise OSError("cut")

        with self.assertRaises(OSError):
            fb.FlowBrowserDriver._save_download(Partial(), staging / "S1.mp4")
        self.assertEqual(list(staging.iterdir()), [])

    def test_empty_file_rejected(self):
        with self.assertRaises(TransientError):
            self._download(FakeDownload(b""))

    def test_cancel_while_waiting(self):
        with self.assertRaises(Cancelled):
            self._download(None, should_stop=lambda: True)

    def test_timeout_while_waiting(self):
        clock = iter(range(0, 10_000, 100))
        with mock.patch.object(fb.time, "monotonic", side_effect=lambda: next(clock)):
            with self.assertRaises(TransientError):
                self._download(None, should_stop=lambda: False)

    def test_compose_prompt_human_typing_and_tags(self):
        with mock.patch("drivers.flow_browser.time.sleep", return_value=None), mock.patch("drivers.stealth.time.sleep", return_value=None):
            d = fb.FlowBrowserDriver(selectors_file="dummy.json")
            page = mock.MagicMock()
            box = mock.MagicMock()
            box.evaluate.return_value = True
            page.locator.return_value.first = box

            opt = mock.MagicMock()
            opt.first.bounding_box.return_value = {"x": 50, "y": 50, "width": 80, "height": 30}
            page.locator.return_value.filter.return_value = opt

            s = {"prompt_box": "box", "ingredient_listbox": "lb", "ingredient_option": "opt"}
            attached = d._compose_prompt(page, s, "Scene with @LOC-1 and @CHAR-1 done", ["LOC-1", "CHAR-1"])
            self.assertEqual(attached, {"LOC-1", "CHAR-1"})
            self.assertTrue(page.keyboard.type.called)


class OverlayTests(unittest.TestCase):
    def test_copy_oserror_is_transient(self):
        tmp = Path(tempfile.mkdtemp())
        img = tmp / "a.png"
        img.write_bytes(b"x")
        job = {"stagingDir": str(tmp / "st"), "referenceImages": [str(img)]}
        with mock.patch.object(helper_overlay.shutil, "copy2", side_effect=OSError("denied")):
            with self.assertRaises(TransientError):
                helper_overlay.prepare_refs_folder(job)


if __name__ == "__main__":
    unittest.main()
