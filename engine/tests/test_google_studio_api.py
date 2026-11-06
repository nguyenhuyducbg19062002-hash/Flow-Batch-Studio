"""Unit test cho GoogleStudioDriver."""
import base64
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from drivers import google_studio_api
from drivers.errors import AuthRequired, Cancelled, ConfigError, QuotaExceeded, Rejected, TransientError
from drivers.google_studio_api import GoogleStudioDriver


class FakeHttpResp:
    def __init__(self, status=200, body=None, raw=b""):
        self.status_code = status
        self._body = body
        self.content = json.dumps(body).encode() if body is not None else raw
        self.text = self.content.decode(errors="ignore")

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise google_studio_api.requests.HTTPError(str(self.status_code))


class FakeSession:
    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    def post(self, url, headers=None, json=None, timeout=None):
        self.calls.append(("POST", url, headers, json))
        return self.replies.pop(0)


def sample_job(tmp_path, kind="IMAGE", refs=None, **extra):
    return {
        "jobId": 10,
        "kind": kind,
        "externalId": "STUDIO-SCENE-01",
        "prompt": "Một khu phố cyberpunk rực rỡ ánh đèn neon về đêm",
        "referenceImages": refs or [],
        "stagingDir": str(tmp_path / "staging"),
        "aspectRatio": "16:9",
        "timeoutSeconds": 60,
        "account": {
            "email": "studio-test",
            "apiKey": "AIzaSyFakeStudioKey123",
        },
        **extra,
    }


class GoogleStudioDriverTests(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.tmp = Path(self.d.name)
        self.addCleanup(self.d.cleanup)

    def test_missing_api_key_raises_config_error(self):
        j = sample_job(self.tmp)
        j["account"]["apiKey"] = ""
        driver = GoogleStudioDriver()
        with self.assertRaises(ConfigError):
            driver.generate(j, lambda: False)

    def test_foreground_mode_rejected(self):
        j = sample_job(self.tmp)
        driver = GoogleStudioDriver()
        with self.assertRaises(Rejected):
            driver.generate_foreground(j, lambda: False)

    def test_image_rest_success(self):
        fake_b64 = base64.b64encode(b"PNG_IMAGE_BYTES").decode("ascii")
        http = FakeSession([
            FakeHttpResp(200, body={"predictions": [{"bytesBase64Encoded": fake_b64, "mimeType": "image/png"}]}),
        ])
        driver = GoogleStudioDriver(http=http)
        j = sample_job(self.tmp, kind="IMAGE")

        # Gọi trực tiếp _image_rest để kiểm thử luồng REST độc lập
        stage = Path(j["stagingDir"])
        res = driver._image_rest(j, "AIzaSyFakeStudioKey123", "imagen-3.0-generate-002", j["prompt"], "16:9", stage)
        self.assertTrue(res.staged_file.is_file())
        self.assertEqual(res.staged_file.read_bytes(), b"PNG_IMAGE_BYTES")
        self.assertEqual(res.staged_file.name, "STUDIO-SCENE-01.png")

        # Kiểm tra request gửi đúng header x-goog-api-key và payload
        method, url, headers, body = http.calls[0]
        self.assertEqual(method, "POST")
        self.assertIn("imagen-3.0-generate-002:predict", url)
        self.assertEqual(headers["x-goog-api-key"], "AIzaSyFakeStudioKey123")
        self.assertEqual(body["instances"][0]["prompt"], j["prompt"])

    def test_image_rest_auth_error(self):
        http = FakeSession([
            FakeHttpResp(403, body={"error": {"code": 403, "status": "PERMISSION_DENIED", "message": "API_KEY_INVALID"}}),
        ])
        driver = GoogleStudioDriver(http=http)
        j = sample_job(self.tmp, kind="IMAGE")
        stage = Path(j["stagingDir"])
        with self.assertRaises(AuthRequired):
            driver._image_rest(j, "BAD_KEY", "imagen-3.0-generate-002", j["prompt"], "16:9", stage)

    def test_image_rest_quota_error(self):
        http = FakeSession([
            FakeHttpResp(429, body={"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "Quota exceeded"}}),
        ])
        driver = GoogleStudioDriver(http=http)
        j = sample_job(self.tmp, kind="IMAGE")
        stage = Path(j["stagingDir"])
        with self.assertRaises(QuotaExceeded):
            driver._image_rest(j, "KEY", "imagen-3.0-generate-002", j["prompt"], "16:9", stage)

    def test_image_rest_empty_prediction_rejected(self):
        http = FakeSession([
            FakeHttpResp(200, body={"predictions": []}),
        ])
        driver = GoogleStudioDriver(http=http)
        j = sample_job(self.tmp, kind="IMAGE")
        stage = Path(j["stagingDir"])
        with self.assertRaises(Rejected):
            driver._image_rest(j, "KEY", "imagen-3.0-generate-002", j["prompt"], "16:9", stage)

    def test_video_too_many_refs_rejected(self):
        tmp_path = self.tmp
        refs = []
        for i in range(5):
            p = tmp_path / f"ref_{i}.png"
            p.write_bytes(b"PNG")
            refs.append(str(p))

        driver = GoogleStudioDriver()
        j = sample_job(self.tmp, kind="VIDEO", refs=refs)
        with self.assertRaises(Rejected) as ctx:
            driver.generate(j, lambda: False)
        self.assertIn("tối đa 3", str(ctx.exception))

    def test_video_generate_with_mock_sdk(self):
        driver = GoogleStudioDriver()
        j = sample_job(self.tmp, kind="VIDEO")

        class FakeOp:
            def __init__(self):
                self.done = True
                self.error = None
                self.response = mock.MagicMock()
                mock_video = mock.MagicMock()
                mock_video.save = lambda path: Path(path).write_bytes(b"VIDEO_DATA_MP4")
                self.response.generated_videos = [mock.MagicMock(video=mock_video)]

        mock_client = mock.MagicMock()
        mock_client.models.generate_videos.return_value = FakeOp()

        with mock.patch("google.genai.Client", return_value=mock_client):
            res = driver.generate(j, lambda: False)
            self.assertTrue(res.staged_file.is_file())
            self.assertEqual(res.staged_file.read_bytes(), b"VIDEO_DATA_MP4")
            self.assertEqual(res.staged_file.name, "STUDIO-SCENE-01.mp4")


if __name__ == "__main__":
    unittest.main()
