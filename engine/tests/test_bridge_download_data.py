"""Extension gửi thẳng file (download_data) khi Chrome bật "Hỏi vị trí lưu": bridge ghép gói, ghi file, báo như lượt tải."""
import base64
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from flow_ext import bridge


class DownloadDataTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.env = mock.patch.dict(os.environ, {"AVS_FLOW_DOWNLOAD_DIR": str(self.dir)})
        self.env.start()
        bridge.S.downloads, bridge.S.xfers = [], {}

    def tearDown(self):
        bridge.S.downloads, bridge.S.xfers = [], {}
        self.env.stop()
        self.tmp.cleanup()

    def _send(self, xid, name, mime, data: bytes, chunk=8, order=None):
        b64 = base64.b64encode(data).decode()
        parts = [b64[i:i + chunk] for i in range(0, len(b64), chunk)] or [""]
        for seq in (order or range(len(parts))):
            bridge._on_download_data({"type": "download_data", "xferId": xid, "name": name, "mime": mime,
                                      "seq": seq, "total": len(parts), "b64": parts[seq]})

    def test_chunks_in_any_order_become_one_file_listed_as_download(self):
        data = bytes(range(256)) * 3
        b64_len = len(base64.b64encode(data))
        n = -(-b64_len // 8)
        self._send("x1", "Woman_in_coat_ab12.jpg", "image/jpeg", data, order=list(reversed(range(n))))
        self.assertEqual(len(bridge.S.downloads), 1)
        got = Path(bridge.S.downloads[0]["filename"])
        self.assertEqual(got, self.dir / "Woman_in_coat_ab12.jpg")
        self.assertEqual(got.read_bytes(), data)
        self.assertEqual(bridge.S.xfers, {})

    def test_same_name_is_not_overwritten(self):
        self._send("a", "x.jpg", "image/jpeg", b"one")
        self._send("b", "x.jpg", "image/jpeg", b"two")
        names = [Path(d["filename"]).name for d in bridge.S.downloads]
        self.assertEqual(names, ["x.jpg", "x (1).jpg"])
        self.assertEqual((self.dir / "x.jpg").read_bytes(), b"one")

    def test_missing_extension_taken_from_mime_and_bad_chars_removed(self):
        self._send("a", "", "image/png", b"png")
        self._send("b", 'c:\evil/..\name', "video/mp4", b"mp4")
        names = [Path(d["filename"]).name for d in bridge.S.downloads]
        self.assertTrue(names[0].startswith("flow-") and names[0].endswith(".png"), names)
        self.assertTrue(names[1].endswith(".mp4") and "\\" not in names[1] and "/" not in names[1], names)
        for n in names:
            self.assertEqual((self.dir / n).parent, self.dir)

    def test_incomplete_or_empty_transfer_writes_nothing(self):
        bridge._on_download_data({"xferId": "p", "name": "a.jpg", "mime": "image/jpeg", "seq": 0, "total": 2, "b64": "AAAA"})
        self._send("e", "e.jpg", "image/jpeg", b"")
        self.assertEqual(bridge.S.downloads, [])
        self.assertEqual(list(self.dir.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
