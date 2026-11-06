"""Test SessionRunner bằng một trang Flow GIẢ mô phỏng đúng những gì đã quan sát trên Flow thật (04/11/2026)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from drivers.errors import (AuthRequired, BotSuspected, Cancelled, GenerationResult, QuotaExceeded, Rejected,  # noqa: E402
                            TransientError, WorkerError)
from drivers.flow_session import SessionRunner, Tile  # noqa: E402


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, s):
        self.now += s


class FakeFlow:
    """Lưới ô giả: mới nhất ở đầu; ô chạy đủ `duration` giây thì xong; kết quả xong LỆCH thứ tự gửi cũng được."""

    def __init__(self, clock, durations, preexisting=2, window=16):
        self.clock, self.durations = clock, durations
        self.window = window   # cuộn ảo của Flow: chỉ khoảng 16 ô mới nhất nằm trong trang
        self.tiles = [{"owner": None, "label": f"old {i}", "done_at": -1} for i in range(preexisting)]
        self.submits, self.downloads = [], []
        self.fail_submit = {}      # jobId -> exception
        self.fail_download = {}    # jobId -> exception
        self.page_error_value = None
        self.grid_calls = 0
        self.fail_tile = {}        # jobId -> chữ Flow ghi trên ô "Failed"

    def generated_tiles(self):
        out = []
        for t in self.tiles:
            done = self.clock.now >= t["done_at"]
            if done and t["owner"] in self.fail_tile:
                out.append(Tile(key=None, label="", pct=None, ready=False, failed=True, error=self.fail_tile[t["owner"]]))
                continue
            key = f"media-{t['owner']}" if (t["owner"] is not None and done) else (f"old-{t['label']}" if t["owner"] is None else None)
            out.append(Tile(key=key, label=t["label"] if not done or t["owner"] is None else f"title {t['owner']}",
                            pct=None if done else int((self.clock.now - t["start"]) * 5) % 100, ready=done))
        return out[: self.window]

    def submit(self, job):
        if job["jobId"] in self.fail_submit:
            raise self.fail_submit[job["jobId"]]
        self.submits.append((job["jobId"], self.clock.now))
        self.tiles.insert(0, {"owner": job["jobId"], "label": job["prompt"], "start": self.clock.now,
                              "done_at": self.clock.now + self.durations[job["jobId"]]})

    def download(self, job, tile):
        assert tile.key == f"media-{job['jobId']}", f"gắn nhầm: job {job['jobId']} nhận ô {tile.key}"
        if job["jobId"] in self.fail_download:
            raise self.fail_download[job["jobId"]]
        self.downloads.append((job["jobId"], self.clock.now))
        return GenerationResult(staged_file=Path(f"{job['externalId']}.png"))

    def page_error(self):
        return self.page_error_value

    def ensure_grid(self):
        self.grid_calls += 1


def job(i, **kw):
    return {"jobId": i, "externalId": f"E{i}", "prompt": f"prompt {i}", "timeoutSeconds": 900, **kw}


class Harness:
    def __init__(self, durations, queue, capacity=3, preexisting=2, window=16, **runner_kw):
        self.clock = Clock()
        self.flow = FakeFlow(self.clock, durations, preexisting=preexisting, window=window)
        self.queue = list(queue)
        self.claims = 0
        self.done, self.failed, self.started = [], [], []
        self.cancelled = set()
        self.max_inflight = 0
        self.runner = SessionRunner(
            self.flow, capacity, self._claim, self.started.append, self._done, self._fail,
            lambda j: (lambda: j["jobId"] in self.cancelled), clock=self.clock, sleep=self.clock.sleep, **runner_kw)

    def _claim(self):
        self.claims += 1
        return self.queue.pop(0) if self.queue else None

    def _done(self, j, result):
        self.done.append((j["jobId"], self.clock.now, result.staged_file.name))

    def _fail(self, j, err):
        self.failed.append((j["jobId"], err))

    def run(self, first):
        orig = self.flow.generated_tiles

        def watch():
            self.max_inflight = max(self.max_inflight, len(self.runner.slots))
            return orig()
        self.flow.generated_tiles = watch
        self.runner.run(first)
        return self


class SessionRunnerTest(unittest.TestCase):
    def test_results_are_bound_to_the_right_job_even_when_finishing_out_of_order(self):
        h = Harness({1: 60, 2: 20, 3: 40}, [job(2), job(3)]).run(job(1))
        self.assertEqual([d[0] for d in h.done], [2, 3, 1], "xong theo thứ tự hoàn thành, không theo thứ tự gửi")
        self.assertEqual({d[0]: d[2] for d in h.done}, {1: "E1.png", 2: "E2.png", 3: "E3.png"})
        self.assertEqual(h.failed, [])
        # cả 3 lệnh được gửi trước khi lệnh nào xong (chồng lên nhau), không cộng dồn 60+20+40
        self.assertLess(max(t for _, t in h.flow.submits), 20)
        self.assertLess(h.clock.now, 70)

    def test_never_exceeds_capacity_and_drains_the_queue(self):
        h = Harness({i: 10 + i for i in range(1, 8)}, [job(i) for i in range(2, 8)], capacity=2).run(job(1))
        self.assertLessEqual(h.max_inflight, 2)
        self.assertEqual(sorted(d[0] for d in h.done), list(range(1, 8)))
        self.assertEqual(h.failed, [])
        self.assertEqual([j["jobId"] for j in h.started], list(range(1, 8)))

    def test_project_with_many_tiles_still_works_because_the_grid_window_is_capped(self):
        # lỗi thật gặp trên Flow: dự án đã có 14+ ô, cuộn ảo chỉ vẽ 16 ô → tổng số ô không tăng khi gửi lệnh mới
        h = Harness({i: 20 for i in range(1, 8)}, [job(i) for i in range(2, 8)], preexisting=40, window=16).run(job(1))
        self.assertEqual(h.failed, [])
        self.assertEqual(sorted(d[0] for d in h.done), list(range(1, 8)))
        self.assertEqual(len(h.flow.submits), 7, "mỗi lệnh chỉ được gửi MỘT lần (không gửi trùng)")

    def test_a_tile_that_scrolled_out_of_the_window_does_not_cause_false_desync(self):
        # cửa sổ rất nhỏ: mỏ neo bị ẩn sau vài lệnh; không được coi là lệch pha
        h = Harness({i: 12 for i in range(1, 9)}, [job(i) for i in range(2, 9)], capacity=2, preexisting=3, window=5).run(job(1))
        self.assertEqual(h.failed, [])
        self.assertEqual(sorted(d[0] for d in h.done), list(range(1, 9)))

    def test_capacity_one_is_the_old_sequential_behaviour(self):
        h = Harness({1: 20, 2: 20}, [job(2)], capacity=1).run(job(1))
        self.assertEqual([t for _, t in h.flow.submits][1] >= 20, True, "lệnh 2 chỉ gửi sau khi lệnh 1 xong")
        self.assertEqual(sorted(d[0] for d in h.done), [1, 2])

    def test_stops_asking_for_more_work_when_none_is_left(self):
        h = Harness({1: 30}, [], capacity=3).run(job(1))
        self.assertEqual([d[0] for d in h.done], [1])
        self.assertLessEqual(h.claims, 12, "không hỏi backend liên tục khi hết việc")

    def test_desync_when_someone_adds_a_tile_by_hand(self):
        h = Harness({1: 50, 2: 50}, [job(2)], capacity=2)
        orig_sleep = h.clock.sleep

        def sleep_and_tamper(s):
            orig_sleep(s)
            if h.clock.now > 8 and not getattr(h, "tampered", False):
                h.tampered = True
                h.flow.tiles.insert(0, {"owner": 999, "label": "tạo bằng tay", "start": h.clock.now, "done_at": h.clock.now + 5})
        h.runner.sleep = sleep_and_tamper
        h.run(job(1))
        self.assertEqual(h.done, [], "không được tải nhầm khi đã lệch")
        self.assertEqual(sorted(j for j, _ in h.failed), [1, 2])
        self.assertTrue(all(isinstance(e, TransientError) and "lệch" in str(e) for _, e in h.failed))

    def test_a_cancelled_job_is_dropped_but_the_others_keep_their_tiles(self):
        h = Harness({1: 30, 2: 30, 3: 30}, [job(2), job(3)])
        h.runner.sleep = lambda s: (h.clock.sleep(s), h.cancelled.add(2) if h.clock.now > 6 else None)[0]
        h.run(job(1))
        self.assertEqual(sorted(d[0] for d in h.done), [1, 3])
        self.assertEqual([(j, type(e)) for j, e in h.failed], [(2, Cancelled)])

    def test_timeout_fails_only_that_job(self):
        h = Harness({1: 10, 2: 9999}, [job(2, timeoutSeconds=40)]).run(job(1))
        self.assertEqual([d[0] for d in h.done], [1])
        self.assertEqual([(j, type(e)) for j, e in h.failed], [(2, TransientError)])

    def test_quota_on_submit_stops_new_work_but_in_flight_jobs_still_finish(self):
        h = Harness({1: 20, 2: 20, 3: 20, 4: 20}, [job(2), job(3), job(4)], capacity=3)
        h.flow.fail_submit[3] = QuotaExceeded("hết lượt")
        h.run(job(1))
        self.assertEqual(sorted(d[0] for d in h.done), [1, 2])
        self.assertEqual([(j, type(e)) for j, e in h.failed], [(3, QuotaExceeded)])
        self.assertNotIn(4, [j["jobId"] for j in h.started], "hết lượt thì không nhận thêm việc")

    def test_auth_problem_fails_every_running_job_with_the_same_error(self):
        h = Harness({1: 50, 2: 50, 3: 50}, [job(2), job(3)])
        h.flow.fail_submit[3] = AuthRequired("hết phiên đăng nhập")
        h.run(job(1))
        self.assertEqual(h.done, [])
        self.assertEqual(sorted(j for j, _ in h.failed), [1, 2, 3])
        self.assertTrue(all(isinstance(e, AuthRequired) for _, e in h.failed))

    def test_a_rejected_prompt_fails_only_itself_and_the_session_continues(self):
        h = Harness({1: 15, 2: 15, 3: 15}, [job(2), job(3)])
        h.flow.fail_submit[1] = Rejected("prompt vi phạm")
        h.run(job(1))
        self.assertEqual([(j, type(e)) for j, e in h.failed], [(1, Rejected)])
        self.assertEqual(sorted(d[0] for d in h.done), [2, 3])

    def test_policy_message_on_page_is_blamed_on_the_latest_submission_once(self):
        h = Harness({1: 30, 2: 30}, [job(2)], capacity=2)
        state = {"shown": False}
        orig_sleep = h.clock.sleep

        def sleep_and_show(s):
            orig_sleep(s)
            if h.clock.now > 4:
                h.flow.page_error_value = ("policy", "violates our policies")   # banner vẫn hiện trong nhiều lần đọc
        h.runner.sleep = sleep_and_show
        h.run(job(1))
        self.assertEqual([(j, type(e)) for j, e in h.failed], [(2, Rejected)], "chỉ lệnh gửi gần nhất bị quy, và chỉ một lần")
        self.assertEqual([d[0] for d in h.done], [1])

    def test_unusual_activity_tile_is_reported_at_once_and_stops_new_work(self):
        h = Harness({1: 10, 2: 10, 3: 10}, [job(2), job(3)], capacity=1)
        h.flow.fail_tile[1] = "Failed We noticed some unusual activity. Note that browser extensions may cause issues."
        h.run(job(1))
        self.assertEqual([(j, type(e)) for j, e in h.failed], [(1, BotSuspected)])
        self.assertLess(h.clock.now, 60, "báo ngay khi ô Failed, không chờ hết giờ 900s")
        self.assertEqual(h.done, [])
        self.assertEqual([j["jobId"] for j in h.started], [1], "tài khoản bị nghi ngờ thì không nhận thêm việc")

    def test_unusual_activity_message_in_chat_panel_stops_new_work_and_reports_suspicious(self):
        # Flow tiếng Việt báo trong khung chat, không có ô lỗi trên lưới
        h = Harness({1: 30, 2: 30, 3: 30}, [job(2), job(3)], capacity=2)
        orig_sleep = h.clock.sleep

        def sleep_and_show(s):
            orig_sleep(s)
            if h.clock.now > 4:
                h.flow.page_error_value = ("suspicious", "hoạt động bất thường")
        h.runner.sleep = sleep_and_show
        h.run(job(1))
        self.assertEqual([(j, type(e)) for j, e in h.failed], [(2, BotSuspected)])
        self.assertEqual([j["jobId"] for j in h.started], [1, 2], "không nhận thêm việc sau khi bị nghi ngờ")
        self.assertEqual([d[0] for d in h.done], [1], "lệnh đã gửi trước đó vẫn được chờ kết quả")

    def test_other_failed_tile_is_a_transient_error_and_session_continues(self):
        h = Harness({1: 10, 2: 10}, [job(2)], capacity=1)
        h.flow.fail_tile[1] = "Failed Something went wrong"
        h.run(job(1))
        self.assertEqual([(j, type(e)) for j, e in h.failed], [(1, TransientError)])
        self.assertEqual([d[0] for d in h.done], [2])

    def test_a_download_error_is_reported_for_that_job_only(self):
        h = Harness({1: 10, 2: 10}, [job(2)])
        h.flow.fail_download[2] = TransientError("tải về lỗi")
        h.run(job(1))
        self.assertEqual([d[0] for d in h.done], [1])
        self.assertEqual([j for j, _ in h.failed], [2])

    def test_unexpected_crash_never_leaves_jobs_hanging(self):
        h = Harness({1: 50, 2: 50}, [job(2)])
        calls = {"n": 0}
        orig = h.flow.generated_tiles

        def boom():
            calls["n"] += 1
            if calls["n"] > 6:
                raise RuntimeError("Edge đã chết")
            return orig()
        h.flow.generated_tiles = boom
        h.run(job(1))
        self.assertEqual(sorted(j for j, _ in h.failed), [1, 2])
        self.assertTrue(all(isinstance(e, TransientError) for _, e in h.failed))
        self.assertEqual(h.done, [])

    def test_every_started_job_is_reported_exactly_once(self):
        h = Harness({i: 5 * i for i in range(1, 6)}, [job(i) for i in range(2, 6)], capacity=3).run(job(1))
        reported = [d[0] for d in h.done] + [j for j, _ in h.failed]
        self.assertEqual(sorted(reported), sorted(j["jobId"] for j in h.started))
        self.assertEqual(len(reported), len(set(reported)))


if __name__ == "__main__":
    unittest.main()
