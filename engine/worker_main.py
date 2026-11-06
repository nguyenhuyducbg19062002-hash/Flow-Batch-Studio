"""
Worker Python: vòng lặp claim → tạo ảnh/video → báo kết quả về backend Spring Boot.

Không đọc/ghi DB. Mọi trạng thái nằm ở backend; worker chết lúc nào cũng được (backend thu hồi job qua heartbeat).
Biến môi trường (PythonWorkerSupervisor đặt sẵn):
  AFM_API           http://127.0.0.1:8082/api/worker
  AFM_WORKER_TOKEN  mã bí mật của lần chạy này
  AFM_WORKER_ID     py-0, py-1, ...
"""
import logging
import os
import sys
import threading
import time
from pathlib import Path

ENGINE_DIR = Path(__file__).resolve().parent
if str(ENGINE_DIR) not in sys.path:
    sys.path.insert(0, str(ENGINE_DIR))

import requests  # noqa: E402

from drivers.errors import ConfigError, TransientError, WorkerError  # noqa: E402

API = os.environ.get("AFM_API", "http://127.0.0.1:8082/api/worker")
WORKER_ID = os.environ.get("AFM_WORKER_ID", f"py-{os.getpid()}")
HTTP = requests.Session()
HTTP.headers["X-Worker-Token"] = os.environ.get("AFM_WORKER_TOKEN", "")

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(WORKER_ID)

_drivers: dict = {}


def driver_for(provider: str):
    """Nạp driver khi cần: máy không cài google-genai vẫn chạy được FLOW_BROWSER."""
    if provider not in _drivers:
        try:
            if provider == "FLOW_BROWSER":
                from drivers.flow_browser import FlowBrowserDriver
                _drivers[provider] = FlowBrowserDriver(ENGINE_DIR / "selectors.json")
            elif provider == "FLOW_EXTENSION":
                if os.environ.get("AVS_FLOW_EXT_MODE", "").strip().lower() == "api":
                    # cách cũ: gọi thẳng API nội bộ của Flow (đã gặp: Flow nhận lệnh mà không tạo video)
                    from drivers.flow_extension import FlowExtensionDriver
                    _drivers[provider] = FlowExtensionDriver()
                else:
                    # mặc định: extension điều khiển giao diện tab Flow (điền prompt, ảnh, bấm Tạo như người dùng)
                    from drivers.flow_extension_ui import FlowExtensionUiDriver
                    _drivers[provider] = FlowExtensionUiDriver(ENGINE_DIR / "selectors.json")
            elif provider == "VERTEX_API":
                from drivers.vertex_api import VertexDriver
                _drivers[provider] = VertexDriver()
            elif provider == "SEEDANCE_API":
                from drivers.seedance_api import SeedanceDriver
                _drivers[provider] = SeedanceDriver()
            elif provider == "GOOGLE_AI_STUDIO":
                from drivers.google_studio_api import GoogleStudioDriver
                _drivers[provider] = GoogleStudioDriver()
            elif provider == "FAKE":
                from drivers.fake_driver import FakeDriver
                _drivers[provider] = FakeDriver()
            else:
                raise ConfigError(f"Provider không hỗ trợ: {provider}")
        except ImportError as e:  # thiếu thư viện trong bản Python đóng gói
            raise ConfigError(f"Thiếu thư viện Python cho {provider}: {e}") from e
    return _drivers[provider]


def call(path: str, body: dict | None = None):
    r = HTTP.post(f"{API}{path}", json=body or {}, timeout=30)
    if r.status_code == 204:
        return None
    r.raise_for_status()
    return r.json() if r.content else None


class Heartbeat:
    """Báo còn sống mỗi 20 giây; nhận lệnh hủy từ backend."""

    def __init__(self, job_id: int):
        self.job_id = job_id
        self.cancelled = threading.Event()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def _run(self):
        while not self._stop.wait(20):
            try:
                resp = call(f"/jobs/{self.job_id}/heartbeat", {"workerId": WORKER_ID})
                if resp and resp.get("cancel"):
                    self.cancelled.set()
            except requests.HTTPError as e:
                if e.response is not None and e.response.status_code in (401, 409):
                    log.warning("Job %s đã bị backend thu hồi hoặc backend đã khởi động lại (%s); dừng", self.job_id,
                                e.response.status_code)
                    self.cancelled.set()
                else:
                    log.warning("heartbeat lỗi: %s", e)
            except requests.RequestException as e:
                log.warning("heartbeat lỗi: %s", e)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()


def report(path: str, body: dict):
    """Báo kết quả, thử lại vài lần: mất một lần báo thì job kẹt tới khi backend thu hồi (5 phút)."""
    for attempt in range(1, 6):
        try:
            call(path, {"workerId": WORKER_ID, **body})
            return
        except requests.HTTPError as e:
            status = e.response.status_code if e.response is not None else None
            if status in (400, 401, 409):  # backend từ chối hẳn: thử lại không giúp gì
                log.error("Backend từ chối báo cáo %s (%s): %s", path, status,
                          e.response.text[:300] if e.response is not None else e)
                return
            log.warning("Báo cáo %s lỗi (lần %d): %s", path, attempt, e)
        except requests.RequestException as e:
            log.warning("Báo cáo %s lỗi (lần %d): %s", path, attempt, e)
        time.sleep(2 * attempt)
    log.error("Không báo được kết quả (%s); backend sẽ thu hồi job sau khi mất heartbeat", path)


def _report_project(job_id: int, url: str):
    try:
        call(f"/jobs/{job_id}/flow-project", {"workerId": WORKER_ID, "url": url})
    except requests.RequestException as e:
        log.warning("Không báo được link dự án Flow: %s", e)


def _report_generated(job: dict, result):
    job_id = job["jobId"]
    staged = Path(result.staged_file)
    if not staged.is_file() or staged.stat().st_size == 0:
        # driver báo xong nhưng không có file: báo lỗi tạm thời thay vì để backend nhận đường dẫn rỗng
        _report_failed(job, TransientError(f"File kết quả không tồn tại hoặc rỗng: {staged.name}"))
        return
    log.info("Job %s xong: %s", job_id, staged)
    report(f"/jobs/{job_id}/generated", {"stagedFile": str(staged), "remoteRef": result.remote_ref,
                                         "mediaUrl": getattr(result, "media_url", None)})


def _report_failed(job: dict, err: Exception):
    job_id = job["jobId"]
    if isinstance(err, WorkerError):
        log.warning("Job %s: %s %s", job_id, err.code, err)
        report(f"/jobs/{job_id}/failed", {"errorCode": err.code, "message": str(err)[:1900]})
    else:  # lỗi không lường trước: coi là tạm thời
        log.error("Job %s lỗi bất ngờ: %s", job_id, err, exc_info=err)
        report(f"/jobs/{job_id}/failed", {"errorCode": "TRANSIENT", "message": f"{type(err).__name__}: {err}"[:1900]})


def wants_session(job: dict) -> bool:
    """Chạy song song (docs/SONG-SONG.md): tài khoản được đặt từ 2 lệnh cùng lúc trở lên, Flow, chế độ chạy ngầm, không phải sửa trên Flow."""
    return (job.get("provider") in ("FLOW_BROWSER", "FAKE") and job.get("runMode") == "BACKGROUND" and not job.get("editInstruction")
            and int((job.get("account") or {}).get("maxConcurrent") or 1) > 1)


def run_session(first_job: dict):
    """Một phiên Flow cho một tài khoản: nhiều job chạy chồng lên nhau, mỗi job có heartbeat riêng và được báo ngay khi xong."""
    account_id = first_job["account"]["id"]
    log.info("Phiên song song: job %s (%s) trên %s, tối đa %s lệnh cùng lúc", first_job["jobId"], first_job["kind"],
             first_job["account"]["email"], first_job["account"].get("maxConcurrent"))
    heartbeats: dict[int, Heartbeat] = {}
    reported: set[int] = set()

    def close(job):
        hb = heartbeats.pop(job["jobId"], None)
        if hb:
            hb.__exit__(None, None, None)

    def on_start(job):
        job_id = job["jobId"]
        log.info("Phiên song song: nhận job %s: %s %s", job_id, job["kind"], job["externalId"])
        job["_on_project"] = lambda url: _report_project(job_id, url)
        hb = Heartbeat(job_id)
        hb.__enter__()
        heartbeats[job_id] = hb

    def on_done(job, result):
        _report_generated(job, result)
        reported.add(job["jobId"])
        close(job)

    def on_fail(job, err):
        _report_failed(job, err)
        reported.add(job["jobId"])
        close(job)

    def stop_of(job):
        hb = heartbeats.get(job["jobId"])
        return hb.cancelled.is_set if hb else (lambda: False)

    def claim_more():
        try:
            return call("/claim-more", {"workerId": WORKER_ID, "accountId": account_id})
        except requests.RequestException as e:
            log.warning("Xin thêm job lỗi: %s", e)
            return None

    try:
        driver_for(first_job["provider"]).run_session(first_job, claim_more, on_start, on_done, on_fail, stop_of)
    except Exception as e:   # lỗi trước khi phiên bắt đầu (mở trình duyệt, vào dự án): job đầu chưa được báo
        if first_job["jobId"] not in reported:
            _report_failed(first_job, e)
            reported.add(first_job["jobId"])
    finally:
        for hb in list(heartbeats.values()):
            hb.__exit__(None, None, None)
        if first_job["jobId"] not in reported:   # lưới an toàn: không để job nào treo ở GENERATING
            _report_failed(first_job, TransientError("Phiên Flow kết thúc mà chưa có kết quả"))


def run_job(job: dict):
    job_id = job.get("jobId")
    if job_id is None:
        log.error("Job không có jobId, bỏ qua: %s", sorted(job))
        return
    if wants_session(job):
        run_session(job)
        return
    with Heartbeat(job_id) as hb:
        try:
            log.info("Job %s: %s %s trên %s (%s, %s)", job_id, job.get("kind"), job.get("externalId"),
                     (job.get("account") or {}).get("email"), job.get("provider"), job.get("runMode"))
            driver = driver_for(job["provider"])
            # driver Flow gọi khi vừa vào / tạo dự án Flow: backend nhớ link theo (phim, tài khoản) ngay lúc đó
            job["_on_project"] = lambda url: _report_project(job_id, url)
            if job.get("runMode") == "FOREGROUND":
                result = driver.generate_foreground(job, should_stop=hb.cancelled.is_set)
            else:
                result = driver.generate(job, should_stop=hb.cancelled.is_set)
            _report_generated(job, result)
        except Exception as e:
            _report_failed(job, e)


def keep_flow_bridge():
    """Giữ cầu nối Flow Extension luôn mở để extension trong Chrome kết nối được ngay, kể cả khi chưa có cảnh nào chạy.
    Worker nào bật trước thì giữ; worker đó tắt thì worker khác bật lại trong vòng 15 giây."""
    def loop():
        try:
            from flow_ext import bridge
        except ImportError as e:   # thiếu thư viện websockets: chỉ FLOW_EXTENSION không dùng được
            log.warning("Không bật được cầu nối Flow Extension: %s", e)
            return
        while True:
            try:
                bridge.ensure_bridge()
            except Exception as e:  # cổng bị chương trình khác giữ...: thử lại sau
                log.debug("Cầu nối Flow Extension chưa bật được: %s", e)
            time.sleep(15)
    threading.Thread(target=loop, name="flow-bridge-keeper", daemon=True).start()


def main():
    try:   # bản engine = giờ sửa file mới nhất (để biết máy đang chạy bản nào khi đọc log)
        import datetime as _dt
        _newest = max(f.stat().st_mtime for f in Path(__file__).resolve().parent.rglob("*.py") if "tests" not in f.parts)
        _build = _dt.datetime.fromtimestamp(_newest).strftime("%Y-%m-%d %H:%M")
    except Exception:  # noqa: BLE001
        _build = "?"
    log.info("Worker %s khởi động, API %s, bản engine %s", WORKER_ID, API, _build)
    keep_flow_bridge()
    misses = 0
    while True:
        try:
            job = call("/claim", {"workerId": WORKER_ID})
            misses = 0
        except requests.RequestException as e:
            misses += 1
            if misses >= 20:  # backend đã tắt khoảng 1 phút: worker tự thoát, không để tiến trình mồ côi
                log.error("Backend không phản hồi, worker thoát. Lỗi cuối: %s", e)
                return
            time.sleep(3)
            continue
        if job is None:
            time.sleep(1)   # backend trả 204 ngay nên hỏi thường xuyên không tốn gì; nhận job mới nhanh hơn
            continue
        run_job(job)


if __name__ == "__main__":
    main()
