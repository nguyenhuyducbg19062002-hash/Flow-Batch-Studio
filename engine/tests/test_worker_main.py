import os as _os  # noqa: E402
for _k, _v in (("AVS_FLOW_WS_PORT", "18421"), ("AVS_FLOW_HTTP_PORT", "18422"), ("AVS_FLOW_CDP_PORT", "18423")):
    _os.environ.setdefault(_k, _v)   # cổng riêng cho test: không nối vào extension Chrome thật trên máy (18321)
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

import requests  # noqa: E402

import worker_main  # noqa: E402
from drivers.errors import GenerationResult  # noqa: E402


class Driver:
    def __init__(self, staged):
        self.staged = staged

    def generate(self, job, should_stop):
        return GenerationResult(staged_file=self.staged)


class RunJobTests(unittest.TestCase):
    def _run(self, job, staged):
        with mock.patch.object(worker_main, "driver_for", return_value=Driver(staged)), \
                mock.patch.object(worker_main, "report") as rep, \
                mock.patch.object(worker_main, "Heartbeat") as hb:
            hb.return_value.__enter__.return_value = hb.return_value
            worker_main.run_job(job)
        return rep

    def test_missing_fields_do_not_crash_and_report_failure(self):
        rep = self._run({"jobId": 7, "provider": "FAKE"}, Path("khong-ton-tai.png"))
        rep.assert_called_once()
        self.assertEqual(rep.call_args[0][0], "/jobs/7/failed")

    def test_missing_job_id_is_ignored(self):
        with mock.patch.object(worker_main, "report") as rep:
            worker_main.run_job({"kind": "IMAGE"})
        rep.assert_not_called()

    def test_empty_staged_file_is_failure(self):
        f = Path(tempfile.mkdtemp()) / "a.png"
        f.write_bytes(b"")
        rep = self._run({"jobId": 1, "provider": "FAKE", "runMode": "BACKGROUND"}, f)
        self.assertEqual(rep.call_args[0][0], "/jobs/1/failed")

    def test_good_staged_file_reported(self):
        f = Path(tempfile.mkdtemp()) / "a.png"
        f.write_bytes(b"x")
        rep = self._run({"jobId": 1, "provider": "FAKE", "runMode": "BACKGROUND"}, f)
        self.assertEqual(rep.call_args[0][0], "/jobs/1/generated")


class HeartbeatTests(unittest.TestCase):
    def test_401_cancels_job(self):
        resp = requests.Response()
        resp.status_code = 401
        hb = worker_main.Heartbeat(1)
        hb._stop = mock.Mock(wait=mock.Mock(side_effect=[False, True]))
        with mock.patch.object(worker_main, "call", side_effect=requests.HTTPError(response=resp)):
            hb._run()
        self.assertTrue(hb.cancelled.is_set())


if __name__ == "__main__":
    unittest.main()
