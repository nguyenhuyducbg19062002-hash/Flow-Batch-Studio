"""SeedanceDriver với HTTP giả: không gọi ModelArk thật."""
import base64
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from drivers import seedance_api  # noqa: E402
from drivers.errors import AuthRequired, ConfigError, QuotaExceeded, Rejected
from drivers.seedance_api import SeedanceDriver


class Resp:
    def __init__(self, status=200, body=None, raw=b""):
        self.status_code = status
        self._body = body
        self.content = json.dumps(body).encode() if body is not None else raw
        self.text = self.content.decode(errors="ignore")

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise seedance_api.requests.HTTPError(str(self.status_code))

    def iter_content(self, _n):
        yield b"MP4DATA"

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeHttp:
    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    def request(self, method, url, json=None, timeout=None, headers=None):
        self.calls.append((method, url, json, headers))
        return self.replies.pop(0)

    def get(self, url, stream=False, timeout=None):
        self.calls.append(("DOWNLOAD", url, None, None))
        return Resp(raw=b"MP4DATA")



def job(tmp_path, kind="VIDEO", refs=None, **extra):
    return {"jobId": 1, "kind": kind, "externalId": "EP06-S1-03", "prompt": "Cảnh 3. Requested duration: 10 seconds",
            "referenceImages": refs or [], "stagingDir": str(tmp_path / "stage"), "aspectRatio": "16:9",
            "videoQuality": "720p", "timeoutSeconds": 60, "durationSeconds": None,
            "account": {"email": "seedance-1", "apiKey": "KEY"}, **extra}


def ref(tmp_path, name):
    p = tmp_path / f"{name}.png"
    p.write_bytes(b"\x89PNG fake")
    return str(p)


class SeedanceDriverTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(seedance_api.time, "sleep", lambda s: None)
        patcher.start()
        self.addCleanup(patcher.stop)
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        self.tmp = Path(d.name)

    def test_video_sends_refs_in_order_with_legend_and_downloads(self):
        tmp_path = self.tmp
        refs = [ref(tmp_path, "NV_AN"), ref(tmp_path, "BOI_CANH_1")]
        http = FakeHttp([Resp(body={"id": "cgt-1"}), Resp(body={"status": "running"}),
                         Resp(body={"status": "succeeded", "content": {"video_url": "https://x/v.mp4"}})])
        res = SeedanceDriver(http).generate(job(tmp_path, refs=refs), lambda: False)
        method, url, body, headers = http.calls[0]
        self.assertEqual(method, "POST")
        self.assertTrue(url.endswith("/api/v3/contents/generations/tasks"))
        self.assertEqual(headers["Authorization"], "Bearer KEY")
        self.assertEqual(body["model"], seedance_api.DEFAULT_VIDEO_MODEL)
        self.assertEqual((body["duration"], body["ratio"], body["resolution"]), (10, "16:9", "720p"))
        self.assertIs(body["generate_audio"], True)
        self.assertTrue(body["content"][0]["text"].startswith("Reference images: [Image 1] = @NV_AN, [Image 2] = @BOI_CANH_1."))
        imgs = body["content"][1:]
        self.assertEqual([i["role"] for i in imgs], ["reference_image", "reference_image"])
        self.assertTrue(imgs[0]["image_url"]["url"].startswith("data:image/png;base64,"))
        self.assertEqual(res.staged_file.read_bytes(), b"MP4DATA")
        self.assertEqual(res.staged_file.name, "EP06-S1-03.mp4")
        self.assertEqual(res.remote_ref, "cgt-1")

    def test_account_models_override_defaults(self):
        j = job(self.tmp, refs=[ref(self.tmp, "A")])
        j["account"]["videoModel"] = "seedance-1-0-lite-i2v-250428"
        j["durationSeconds"] = 5
        http = FakeHttp([Resp(body={"id": "t"}), Resp(body={"status": "succeeded", "content": {"video_url": "u"}})])
        SeedanceDriver(http).generate(j, lambda: False)
        self.assertEqual(http.calls[0][2]["model"], "seedance-1-0-lite-i2v-250428")
        self.assertEqual(http.calls[0][2]["duration"], 5)
        self.assertNotIn("generate_audio", http.calls[0][2])   # Seedance 1.0 không có tiếng

    def test_mini_model_caps_resolution_at_720p(self):
        j = job(self.tmp, refs=[ref(self.tmp, "A")], videoQuality="1080p")
        http = FakeHttp([Resp(body={"id": "t"}), Resp(body={"status": "succeeded", "content": {"video_url": "u"}})])
        SeedanceDriver(http).generate(j, lambda: False)
        self.assertEqual(http.calls[0][2]["model"], "dreamina-seedance-2-0-mini-260615")
        self.assertEqual(http.calls[0][2]["resolution"], "720p")

    def test_sound_off_for_project(self):
        j = job(self.tmp, refs=[ref(self.tmp, "A")], generateAudio=False)
        j["aspectRatio"] = "adaptive"
        http = FakeHttp([Resp(body={"id": "t"}), Resp(body={"status": "succeeded", "content": {"video_url": "u"}})])
        SeedanceDriver(http).generate(j, lambda: False)
        self.assertIs(http.calls[0][2]["generate_audio"], False)
        self.assertEqual(http.calls[0][2]["ratio"], "adaptive")

    def test_failed_task_is_rejected(self):
        http = FakeHttp([Resp(body={"id": "t"}), Resp(body={"status": "failed",
                         "error": {"code": "OutputVideoSensitiveContentDetected", "message": "x"}})])
        with self.assertRaisesRegex(Rejected, "OutputVideoSensitiveContentDetected"):
            SeedanceDriver(http).generate(job(self.tmp, refs=[ref(self.tmp, "A")]), lambda: False)

    def test_http_errors_map_to_worker_errors(self):
        for status, code, err in [(401, "AuthenticationError", AuthRequired),
                                  (429, "RateLimitExceeded.EndpointRPMExceeded", QuotaExceeded),
                                  (404, "ModelNotOpen", ConfigError), (400, "InvalidParameter", Rejected)]:
            with self.subTest(code=code):
                http = FakeHttp([Resp(status, {"error": {"code": code, "message": "m"}})])
                with self.assertRaises(err):
                    SeedanceDriver(http).generate(job(self.tmp, refs=[ref(self.tmp, "A")]), lambda: False)

    def test_missing_key_is_config_error(self):
        j = job(self.tmp)
        j["account"]["apiKey"] = None
        with self.assertRaises(ConfigError):
            SeedanceDriver(FakeHttp([])).generate(j, lambda: False)

    def test_too_many_refs_rejected_without_calling_api(self):
        refs = [ref(self.tmp, f"R{i}") for i in range(seedance_api.MAX_REFS + 1)]
        http = FakeHttp([])
        with self.assertRaises(Rejected):
            SeedanceDriver(http).generate(job(self.tmp, refs=refs), lambda: False)
        self.assertEqual(http.calls, [])

    def test_cancel_deletes_task(self):
        http = FakeHttp([Resp(body={"id": "t9"}), Resp(body={})])
        with self.assertRaises(seedance_api.Cancelled):
            SeedanceDriver(http).generate(job(self.tmp, refs=[ref(self.tmp, "A")]), lambda: True)
        self.assertEqual(http.calls[1][0], "DELETE")
        self.assertTrue(http.calls[1][1].endswith("/tasks/t9"))

    def test_image_uses_seedream_and_writes_png(self):
        png = base64.b64encode(b"PNGBYTES").decode()
        http = FakeHttp([Resp(body={"data": [{"b64_json": png}]})])
        res = SeedanceDriver(http).generate(job(self.tmp, kind="IMAGE", refs=[ref(self.tmp, "NV_AN")]), lambda: False)
        _, url, body, _ = http.calls[0]
        self.assertTrue(url.endswith("/api/v3/images/generations"))
        self.assertEqual(body["model"], seedance_api.DEFAULT_IMAGE_MODEL)
        self.assertEqual(len(body["image"]), 1)
        self.assertEqual(res.staged_file.read_bytes(), b"PNGBYTES")

    def test_asset_file_sends_asset_ids_instead_of_file(self):
        refs = [ref(self.tmp, "CHAR-CLARA-1"), ref(self.tmp, "PANEL-1")]
        (self.tmp / "modelark-assets.txt").write_text("# ghi chu\n@CHAR-CLARA-1 = asset-abc123\n", encoding="utf-8")
        http = FakeHttp([Resp(body={"id": "t"}), Resp(body={"status": "succeeded", "content": {"video_url": "u"}})])
        SeedanceDriver(http).generate(job(self.tmp, refs=refs), lambda: False)
        urls = [c["image_url"]["url"] for c in http.calls[0][2]["content"][1:]]
        self.assertEqual(urls[0], "asset://asset-abc123")
        self.assertTrue(urls[1].startswith("data:image/png;base64,"))

    def test_real_person_error_names_the_images(self):
        refs = [ref(self.tmp, "PANEL-1"), ref(self.tmp, "CHAR-CLARA-1"), ref(self.tmp, "BG-1"), ref(self.tmp, "CHAR-BRAD-1")]
        msg = "The request failed because the input image 'content[2]' 'content[4]' may contain real person."
        http = FakeHttp([Resp(400, {"error": {"code": "InputImageSensitiveContentDetected.PrivacyInformation", "message": msg}})])
        with self.assertRaisesRegex(Rejected, "@CHAR-CLARA-1, @CHAR-BRAD-1: ảnh trông như người thật"):
            SeedanceDriver(http).generate(job(self.tmp, refs=refs), lambda: False)


if __name__ == "__main__":
    unittest.main()
