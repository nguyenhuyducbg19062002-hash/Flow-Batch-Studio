"""Bấm Tạo mà Flow không nhận lệnh (không có ô mới nào): báo lỗi sau NO_START_S để cảnh thử lại, không đứng chờ 15 phút."""
import sys
import unittest
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

from drivers import flow_browser as fb  # noqa: E402
from drivers.flow_session import Tile  # noqa: E402

S = {"result_card": "card", "prompt_box": "box", "agent_reply_marker": "TODO"}
OLD = Tile(key="old-1", label="ảnh cũ", pct=None, ready=True)


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def driver(tiles_seq, media_seq=None):
    d = fb.FlowBrowserDriver.__new__(fb.FlowBrowserDriver)
    d._pace = fb.pace_mod.NONE
    d._error_tiles = lambda page, s: []
    d._raise_if_error = lambda *a, **k: None
    d._log_tiles = lambda *a, **k: None
    media = iter(media_seq or [])
    d._media_ids = lambda page, s, want=None, **k: next(media, [])
    d._media_ready = lambda page, m: True
    seq = iter(tiles_seq)
    last = {"v": tiles_seq[0]}

    def tiles(self):
        last["v"] = next(seq, last["v"])
        return last["v"]
    return d, tiles


class NoStartTests(unittest.TestCase):
    def setUp(self):
        self.page = mock.MagicMock()
        self.page.url = "https://flow.google.com/project/x"
        self.clock = Clock()
        self.page.wait_for_timeout.side_effect = lambda ms: setattr(self.clock, "t", self.clock.t + ms / 1000)
        for p in (mock.patch.object(fb, "detect_and_solve_turnstile", lambda *a, **k: False),
                  mock.patch.object(fb.time, "monotonic", self.clock),
                  mock.patch.object(fb._SessionOps, "_prompt_box_empty", lambda self: True)):
            p.start()
            self.addCleanup(p.stop)

    def _run(self, d, tiles, timeout_s=900):
        with mock.patch.object(fb._SessionOps, "generated_tiles", tiles):
            d._sent_probe = d._sent_snapshot(self.page, S, "a woman in a coat")
            return d._wait_result(self.page, S, {"old-1"}, {}, lambda: False, timeout_s, "IMAGE")

    def test_no_new_tile_fails_fast_instead_of_waiting_full_timeout(self):
        d, tiles = driver([[OLD]])
        with self.assertRaises(fb.TransientError) as cm:
            self._run(d, tiles)
        self.assertIn("không nhận lệnh", str(cm.exception))
        self.assertLess(self.clock.t - 1000, fb.NO_START_S + 10)

    def test_running_tile_keeps_waiting_until_result(self):
        busy = Tile(key=None, label="a woman in a coat", pct=12, ready=False)
        # ô mới đang chạy rất lâu (lâu hơn NO_START_S) rồi mới xong: không được báo lỗi
        d, tiles = driver([[OLD], [busy, OLD]])
        done_at = 1000 + fb.NO_START_S + 30
        d._media_ids = lambda page, s, want=None, **k: (["new-1"] if self.clock.t > done_at else []) + ["old-1"]
        self.assertEqual(self._run(d, tiles), "new-1")
        self.assertGreater(self.clock.t, done_at)

    def test_without_snapshot_old_behaviour_is_kept(self):
        d, tiles = driver([[OLD]], [[]] * 200 + [["new-1"]])
        with mock.patch.object(fb._SessionOps, "generated_tiles", tiles):
            got = d._wait_result(self.page, S, {"old-1"}, {}, lambda: False, 900, "IMAGE")
        self.assertEqual(got, "new-1")


if __name__ == "__main__":
    unittest.main()


class ChatResultTests(unittest.TestCase):
    """Tác nhân trả ảnh vào khung chat, lưới không có ô mới (2026-11-07, CHAR-CLARA-5): tự bấm ảnh, lấy media id từ /edit/."""

    def setUp(self):
        self.page = mock.MagicMock()
        self.page.url = "https://flow.google.com/project/11111111-1111-1111-1111-111111111111"
        self.clock = Clock()
        self.page.wait_for_timeout.side_effect = lambda ms: setattr(self.clock, "t", self.clock.t + ms / 1000)
        for p in (mock.patch.object(fb, "detect_and_solve_turnstile", lambda *a, **k: False),
                  mock.patch.object(fb.time, "monotonic", self.clock),
                  mock.patch.object(fb, "human_click", self._click),
                  mock.patch.object(fb._SessionOps, "_prompt_box_empty", lambda self: True)):
            p.start()
            self.addCleanup(p.stop)
        self.clicked = 0
        self.open_id = "22222222-2222-2222-2222-222222222222"

    def _click(self, page, loc, delay_before=0):
        self.clicked += 1
        page.url = page.url + "/edit/" + self.open_id

    def _driver(self, chat_seq):
        d, tiles = driver([[OLD]])
        seq = iter(chat_seq)
        last = {"v": chat_seq[0]}

        def chat(page, s):
            last["v"] = next(seq, last["v"])
            return last["v"]
        d._chat_images = chat
        self.page.evaluate.side_effect = lambda js, *a: (["old-1"] if "data-media-id" in js else True)
        return d, tiles

    def _run(self, d, tiles):
        with mock.patch.object(fb._SessionOps, "generated_tiles", tiles):
            d._sent_probe = d._sent_snapshot(self.page, S, "clara")
            return d._wait_result(self.page, S, {"old-1"}, {}, lambda: False, 900, "IMAGE")

    def test_new_chat_image_is_clicked_and_its_media_id_returned(self):
        d, tiles = self._driver([["chat-old"], ["chat-old"], ["chat-old", "chat-new"]])
        self.assertEqual(self._run(d, tiles), self.open_id)
        self.assertEqual(self.clicked, 1)

    def test_opening_an_old_media_is_not_a_result(self):
        self.open_id = "old-1"
        d, tiles = self._driver([["chat-old"], ["chat-old", "chat-ref"]])
        with self.assertRaises(fb.TransientError):
            self._run(d, tiles)
        self.assertEqual(self.clicked, 1)   # không bấm lại cùng ảnh


class OutfitVariantResultTests(unittest.TestCase):
    """Cảnh có ảnh tham chiếu, tạo trang phục khác: Tác nhân đặt tên ảnh KẾT QUẢ theo ảnh tham chiếu (2026-11-07: kết quả của
    CHAR-ETHAN-3 mang tên "CHAR-ETHAN-1.jpg"). Phải nhận theo khoá mới, không bỏ qua vì tên giống file tải lên."""

    def setUp(self):
        self.page = mock.MagicMock()
        self.page.url = "https://flow.google.com/project/x"
        self.clock = Clock()
        self.page.wait_for_timeout.side_effect = lambda ms: setattr(self.clock, "t", self.clock.t + ms / 1000)
        for p in (mock.patch.object(fb, "detect_and_solve_turnstile", lambda *a, **k: False),
                  mock.patch.object(fb.time, "monotonic", self.clock),
                  mock.patch.object(fb._SessionOps, "_prompt_box_empty", lambda self: True)):
            p.start()
            self.addCleanup(p.stop)

    def test_result_named_like_reference_file_is_found(self):
        d, tiles = driver([[OLD]])
        state = {"sent": False}

        def media_ids(page, s, want=None, uploaded=False):
            # lưới: ô cũ "old-1", ảnh tham chiếu "ref-1" (tên CHAR-ETHAN-1.jpg); sau khi bấm Tạo có thêm "new-x" cũng tên
            # CHAR-ETHAN-1.jpg -> cách lọc theo tên (uploaded=False) không bao giờ thấy nó
            if not uploaded:
                return ["old-1"]
            return (["new-x"] if state["sent"] else []) + ["old-1", "ref-1"]
        d._media_ids = media_ids
        d._chat_images = lambda page, s: []
        with mock.patch.object(fb._SessionOps, "generated_tiles", tiles):
            d._sent_probe = d._sent_snapshot(self.page, S, "ethan denim")
            state["sent"] = True
            got = d._wait_result(self.page, S, {"old-1"}, {}, lambda: False, 900, "IMAGE")
        self.assertEqual(got, "new-x")
        self.assertLess(self.clock.t - 1000, 10)   # nhận ngay, không chờ / không tự trả lời Tác nhân
