"""Ô lỗi trên Flow ("Không thành công…"): bấm ↻ thử lại vài lần; lỗi chính sách / hết lượt thì báo luôn."""
import sys
import unittest
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

from drivers import flow_browser as fb  # noqa: E402

S = {"result_card": "flow-grid-tile-container", "agent_reply_marker": "TODO", "policy_texts": ["vi phạm chính sách"],
     "quota_texts": ["hết tín dụng"], "suspicious_texts": ["hoạt động bất thường"]}
BOT = " | warning Không thành công Chúng tôi nhận thấy có hoạt động bất thường"


def driver(tiles_seq, media_seq):
    d = fb.FlowBrowserDriver.__new__(fb.FlowBrowserDriver)
    d._pace = fb.pace_mod.NONE
    tiles = iter(tiles_seq)
    last = {"v": []}

    def error_tiles(page, s):
        last["v"] = next(tiles, last["v"])
        return last["v"]
    d._error_tiles = error_tiles
    media = iter(media_seq)
    d._media_ids = lambda page, s, want=None, **k: next(media, [])
    d._media_ready = lambda page, m: True
    d._raise_if_error = lambda *a, **k: None
    d._retry_error_tile = mock.MagicMock(return_value=True)
    return d


class RetryTests(unittest.TestCase):
    def setUp(self):
        self.page = mock.MagicMock()
        self.page.url = "https://flow.google.com/project/x"
        patcher = mock.patch.object(fb, "detect_and_solve_turnstile", lambda *a, **k: False)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_bot_error_tile_is_retried_then_result_returned(self):
        # trước khi bấm Tạo: không có ô lỗi; sau đó ô lỗi → bấm ↻; lượt sau có ảnh mới
        d = driver([[], [BOT], [BOT], [BOT]], [[], [], ["new-1"], ["new-1"]])
        got = d._wait_result(self.page, S, set(), {}, lambda: False, 60, "IMAGE")
        self.assertEqual(got, "new-1")
        d._retry_error_tile.assert_called_once()

    def test_policy_error_is_not_retried(self):
        d = driver([[], [" | Không thành công vi phạm chính sách"]], [[]])
        with self.assertRaises(fb.Rejected):
            d._wait_result(self.page, S, set(), {}, lambda: False, 60, "IMAGE")
        d._retry_error_tile.assert_not_called()

    def test_gives_up_after_retries(self):
        with mock.patch.dict("os.environ", {"AFM_FLOW_TILE_RETRIES": "1"}):
            d = driver([[], [BOT], [BOT], [BOT, BOT + " 2"]], [[]] * 10)
            with self.assertRaises(fb.BotSuspected):
                d._wait_result(self.page, S, set(), {}, lambda: False, 60, "IMAGE")
        d._retry_error_tile.assert_called_once()


if __name__ == "__main__":
    unittest.main()
