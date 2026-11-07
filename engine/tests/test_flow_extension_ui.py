"""FLOW_EXTENSION (điều khiển giao diện tab Flow): nhận file tải về, chờ tab rảnh."""
import os as _os  # noqa: E402
for _k, _v in (("AVS_FLOW_WS_PORT", "18421"), ("AVS_FLOW_HTTP_PORT", "18422"), ("AVS_FLOW_CDP_PORT", "18423")):
    _os.environ.setdefault(_k, _v)   # cổng riêng cho test: không nối vào extension Chrome thật trên máy (18321)
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ENGINE))

from drivers import flow_extension_ui as ui  # noqa: E402
from drivers.errors import Cancelled, TransientError  # noqa: E402


class TakeDownloadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.staging = self.dir / "staging"
        self.staging.mkdir()
        self.dl = self.dir / "Downloads"
        self.dl.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def file(self, name, data=b"x" * 100):
        p = self.dl / name
        p.write_bytes(data)
        return {"filename": str(p), "at": time.time()}

    def test_takes_latest_file_of_right_kind(self):
        items = [self.file("old.mp4", b"1" * 10), self.file("poster.png"), self.file("Flow_video.MP4", b"2" * 20)]
        with mock.patch.object(ui.bridge, "downloads", return_value=items):
            got = ui.FlowExtensionUiDriver._take_download(0, "VIDEO", self.staging, "kb-ha_011")
        self.assertEqual(got, self.staging / "kb-ha_011.mp4")
        self.assertEqual(got.read_bytes(), b"2" * 20)

    def test_image_ignores_video_and_missing_or_empty_files(self):
        items = [self.file("clip.mp4"), self.file("empty.png", b""), {"filename": str(self.dl / "gone.png")}]
        with mock.patch.object(ui.bridge, "downloads", return_value=items):
            self.assertIsNone(ui.FlowExtensionUiDriver._take_download(0, "IMAGE", self.staging, "CHAR-A-1"))
        items.append(self.file("img.jpeg"))
        with mock.patch.object(ui.bridge, "downloads", return_value=items):
            got = ui.FlowExtensionUiDriver._take_download(0, "IMAGE", self.staging, "CHAR-A-1")
        self.assertEqual(got.name, "CHAR-A-1.jpeg")
        self.assertFalse(list(self.staging.glob("*.part")))


class WaitTabTest(unittest.TestCase):
    def setUp(self):
        mock.patch.object(ui.bridge, "ensure_bridge").start()
        mock.patch.object(ui.time, "sleep").start()

    def tearDown(self):
        mock.patch.stopall()

    def test_waits_while_another_scene_uses_the_tab(self):
        answers = iter([{"connected": True, "cdpBusy": True}, {"connected": True, "cdpBusy": True},
                        {"connected": True, "cdpBusy": False}])
        st = mock.patch.object(ui.bridge, "status", side_effect=lambda *a: next(answers)).start()
        ui.FlowExtensionUiDriver()._wait_tab(lambda: False)
        self.assertEqual(st.call_count, 3)

    def test_no_extension_gives_up_after_wait(self):
        mock.patch.object(ui.bridge, "status", return_value={"connected": False}).start()
        with mock.patch.object(ui, "EXTENSION_WAIT_S", 0), self.assertRaises(TransientError):
            ui.FlowExtensionUiDriver()._wait_tab(lambda: False)

    def test_asks_status_of_the_jobs_own_chrome(self):
        st = mock.patch.object(ui.bridge, "status", return_value={"connected": True, "cdpBusy": False}).start()
        d = ui.FlowExtensionUiDriver()
        d._use_job({"account": {"email": "B@x.com"}})
        d._wait_tab(lambda: False)
        st.assert_called_with("b@x.com")

    def test_missing_chrome_of_account_names_the_email(self):
        mock.patch.object(ui.bridge, "status", return_value={"connected": False}).start()
        d = ui.FlowExtensionUiDriver()
        d._use_job({"account": {"email": "b@x.com"}})
        with mock.patch.object(ui, "EXTENSION_WAIT_S", 0), self.assertRaisesRegex(TransientError, "b@x.com"):
            d._wait_tab(lambda: False)

    def test_cancel_while_waiting(self):
        mock.patch.object(ui.bridge, "status", return_value={"connected": True, "cdpBusy": True}).start()
        with self.assertRaises(Cancelled):
            ui.FlowExtensionUiDriver()._wait_tab(lambda: True)


if __name__ == "__main__":
    unittest.main()
