"""FLOW_EXTENSION: bridge (WebSocket <-> HTTP) và driver, với extension / Flow giả."""
import asyncio
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ENGINE))
os.environ.setdefault("AVS_FLOW_WS_PORT", "18421")
os.environ.setdefault("AVS_FLOW_HTTP_PORT", "18422")
os.environ.setdefault("AVS_FLOW_CDP_PORT", "18423")
os.environ["AVS_FLOW_MIN_GAP_S"] = "0"

from drivers import flow_batch as fb  # noqa: E402
from drivers import flow_extension as fx  # noqa: E402
from drivers.errors import AuthRequired, BotSuspected, QuotaExceeded, Rejected, TransientError  # noqa: E402
from flow_ext import bridge  # noqa: E402

PROJECT = "11111111-2222-3333-4444-555555555555"
OP = "aaaaaaaa-0000-0000-0000-000000000001"
VIDEO_MEDIA = "bbbbbbbb-0000-0000-0000-000000000002"
IMAGE_MEDIA = "cccccccc-0000-0000-0000-000000000003"


def envelope(rpcid, payload):
    return ")]}'\n" + json.dumps([["wrb.fr", rpcid, json.dumps(payload), None, None, None, "generic"]])


class FakeResponse:
    def __init__(self, content=b"x" * 4096, status=200):
        self.content, self.status_code = content, status
        self.ok = status < 400


class BridgeTest(unittest.TestCase):
    def test_roundtrip_through_fake_extension(self):
        import websockets

        bridge.ensure_bridge()
        seen = []

        async def fake_extension(stop):
            async with websockets.connect(f"ws://127.0.0.1:{bridge.WS_PORT}",
                                          origin="chrome-extension://abcdefghijklmnop") as ws:
                await ws.send(json.dumps({"type": "extension_ready", "extensionVersion": "test"}))
                while not stop.is_set():
                    try:
                        msg = json.loads(await asyncio.wait_for(ws.recv(), 0.2))
                    except asyncio.TimeoutError:
                        continue
                    if msg.get("method") == "batch_rpc":
                        seen.append(msg["params"])
                        await ws.send(json.dumps({"id": msg["id"], "status": 200, "data": "OK:" + msg["params"]["rpcid"]}))

        stop = threading.Event()
        t = threading.Thread(target=lambda: asyncio.run(fake_extension(stop)), daemon=True)
        t.start()
        try:
            for _ in range(50):
                if bridge.status()["connected"]:
                    break
                time.sleep(0.1)
            self.assertTrue(bridge.status()["connected"])
            out = bridge.rpc("ogiZ0b", "[freq]", "IMAGE_GENERATION", timeout=10)
            self.assertEqual(out, {"id": out.get("id"), "status": 200, "data": "OK:ogiZ0b"})
            self.assertEqual(seen[0]["captchaAction"], "IMAGE_GENERATION")
        finally:
            stop.set()
            t.join(5)
        for _ in range(50):
            if not bridge.status()["connected"]:
                break
            time.sleep(0.1)
        self.assertEqual(bridge.rpc("jwpduf", "[x]", timeout=5), {"error": "NO_EXTENSION"})

    def test_rejects_browser_pages(self):
        import requests
        bridge.ensure_bridge()
        r = requests.post(f"{bridge.BRIDGE_URL}/rpc", json={}, headers={"Origin": "https://evil.example"})
        self.assertEqual(r.status_code, 403)
        r = requests.post(f"{bridge.BRIDGE_URL}/rpc", json={})
        self.assertEqual(r.status_code, 403)

    def test_cdp_relay_and_downloads(self):
        """Playwright (giả) nối cổng CDP: lệnh trình duyệt bridge tự trả lời, lệnh trang chuyển qua extension (giả)."""
        import websockets

        bridge.ensure_bridge()
        forwarded = []

        async def scenario():
            async with websockets.connect(f"ws://127.0.0.1:{bridge.WS_PORT}",
                                          origin="chrome-extension://abcdefghijklmnop") as ext:
                await ext.send(json.dumps({"type": "extension_ready", "extensionVersion": "test"}))

                async def extension():
                    async for raw in ext:
                        m = json.loads(raw)
                        if m.get("method") == "cdp_attach":
                            await ext.send(json.dumps({"id": m["id"], "result": {"targetInfo": {"targetId": "T1", "type": "page",
                                                                                                "url": "https://flow.google.com/"}}}))
                        elif m.get("method") == "cdp":
                            forwarded.append(m["params"])
                            await ext.send(json.dumps({"type": "cdp_event", "method": "Page.loadEventFired", "params": {}}))
                            await ext.send(json.dumps({"id": m["id"], "result": {"ok": m["params"]["method"]}}))
                        elif m.get("method") == "cdp_detach":
                            await ext.send(json.dumps({"id": m["id"], "result": {}}))
                            await ext.send(json.dumps({"type": "download", "filename": "C:/Downloads/x.mp4", "mime": "video/mp4"}))

                task = asyncio.create_task(extension())
                for _ in range(50):
                    if bridge.S.ws is not None:
                        break
                    await asyncio.sleep(0.05)
                async with websockets.connect(bridge.CDP_URL) as pw:
                    async def call(obj):
                        await pw.send(json.dumps(obj))
                        msgs = []
                        while True:
                            m = json.loads(await asyncio.wait_for(pw.recv(), 5))
                            msgs.append(m)
                            if m.get("id") == obj["id"]:
                                return msgs

                    got = await call({"id": 1, "method": "Browser.getVersion"})
                    self.assertEqual(got[-1]["result"]["protocolVersion"], "1.3")
                    got = await call({"id": 2, "method": "Target.setAutoAttach", "params": {"autoAttach": True, "flatten": True}})
                    self.assertEqual(got[0]["method"], "Target.attachedToTarget")
                    self.assertEqual(got[0]["params"]["sessionId"], bridge.CDP_PAGE_SESSION)
                    self.assertEqual(got[0]["params"]["targetInfo"]["targetId"], "T1")
                    got = await call({"id": 3, "method": "Runtime.evaluate", "sessionId": bridge.CDP_PAGE_SESSION})
                    self.assertEqual(got[0], {"method": "Page.loadEventFired", "params": {}, "sessionId": bridge.CDP_PAGE_SESSION})
                    self.assertEqual(got[-1], {"id": 3, "sessionId": bridge.CDP_PAGE_SESSION, "result": {"ok": "Runtime.evaluate"}})
                    await call({"id": 4, "method": "DOM.enable", "sessionId": "child-1"})
                    # Playwright thứ hai bị từ chối khi tab đang bận
                    async with websockets.connect(bridge.CDP_URL) as other:
                        with self.assertRaises(websockets.ConnectionClosed):
                            await asyncio.wait_for(other.recv(), 5)
                for _ in range(50):
                    if bridge.S.downloads:
                        break
                    await asyncio.sleep(0.05)
                task.cancel()

        since = time.time() - 1
        asyncio.run(scenario())
        self.assertEqual(forwarded[0], {"method": "Runtime.evaluate", "params": {}, "sessionId": None})
        self.assertEqual(forwarded[1]["sessionId"], "child-1")
        self.assertEqual([d["filename"] for d in bridge.downloads(since)], ["C:/Downloads/x.mp4"])
        self.assertFalse(bridge.status()["cdpBusy"])


class DriverTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.env = mock.patch.dict(os.environ, {"LOCALAPPDATA": str(self.dir / "appdata")})
        self.env.start()
        fx.MEDIA_CACHE.path = fx._data_dir() / "flow_ext_media.json"
        fx.PROJECT_CACHE.path = fx._data_dir() / "flow_ext_projects.json"
        self.ref = self.dir / "CHAR-A-1.png"
        self.ref.write_bytes(b"\x89PNG" + b"0" * 2000)
        self.calls = []
        mock.patch.object(fx.bridge, "ensure_bridge").start()
        mock.patch.object(fx.time, "sleep").start()
        self.connected = mock.patch.object(fx.FlowExtensionDriver, "_connected", return_value=True).start()

    def tearDown(self):
        mock.patch.stopall()
        self.env.stop()
        self.tmp.cleanup()

    def job(self, kind, refs=(), **kw):
        j = {"jobId": 1, "kind": kind, "externalId": "X-1", "prompt": "a cat", "referenceImages": [str(r) for r in refs],
             "stagingDir": str(self.dir / "staging"), "flowProjectUrl": f"https://flow.google.com/project/{PROJECT}",
             "aspectRatio": "16:9", "timeoutSeconds": 600, "account": {"id": 7}}
        j.update(kw)
        return j

    def fake_rpc(self, responses):
        def rpc(rpcid, freq, captcha=None, match=None, timeout=120):
            self.calls.append((rpcid, freq, captcha, match))
            r = responses[rpcid]
            return r(freq) if callable(r) else r
        mock.patch.object(fx.bridge, "rpc", side_effect=rpc).start()

    def test_image_with_reference_uploads_once(self):
        img_url = f"https://flow-content.google/image/{IMAGE_MEDIA}?sig=1"
        self.fake_rpc({
            fb.RPC_UPLOAD_IMAGE: {"status": 200, "data": envelope(fb.RPC_UPLOAD_IMAGE, [["ref-media-1", PROJECT, "op", "CAE"]])},
            fb.RPC_GEN_IMAGE: {"status": 200, "data": envelope(fb.RPC_GEN_IMAGE, [[[None, [img_url]]]])},
        })
        get = mock.patch.object(fx.requests, "get", return_value=FakeResponse(b"\xff\xd8\xff" + b"1" * 5000)).start()
        res = fx.FlowExtensionDriver().generate(self.job("IMAGE", [self.ref]), lambda: False)
        self.assertEqual(res.staged_file.name, "X-1.jpg")
        self.assertEqual(res.remote_ref, IMAGE_MEDIA)
        get.assert_called_once_with(img_url, timeout=300)
        gen = next(c for c in self.calls if c[0] == fb.RPC_GEN_IMAGE)
        self.assertIn("ref-media-1", gen[1])
        self.assertIn(PROJECT, gen[1])
        self.assertEqual(gen[2], fb.CAPTCHA_IMAGE)
        # lần sau: ảnh tham chiếu đã nhớ media id, không upload lại
        fx.FlowExtensionDriver().generate(self.job("IMAGE", [self.ref]), lambda: False)
        self.assertEqual(sum(1 for c in self.calls if c[0] == fb.RPC_UPLOAD_IMAGE), 1)

    def test_video_with_references_polls_until_video_url(self):
        video_url = f"https://flow-content.google/video/{VIDEO_MEDIA}?sig=2"
        media_answers = iter([
            envelope(fb.RPC_MEDIA, [[f"https://flow-content.google/image/{VIDEO_MEDIA}?poster"]]),   # mới có ảnh bìa
            envelope(fb.RPC_MEDIA, [[f"https://flow-content.google/image/{VIDEO_MEDIA}?poster", video_url]]),
        ])
        self.fake_rpc({
            fb.RPC_UPLOAD_IMAGE: {"status": 200, "data": envelope(fb.RPC_UPLOAD_IMAGE, [["ref-media-1"]])},
            fb.RPC_GEN_VIDEO_REFERENCES: {"status": 200, "data": envelope(fb.RPC_GEN_VIDEO_REFERENCES, [None, 50, [[OP, PROJECT, "scene", None]]])},
            fb.RPC_OPERATION: {"status": 200, "data": envelope(fb.RPC_OPERATION, [None, 50, [[OP, PROJECT, "scene", "CAE"]]])},
            fb.RPC_PROJECT_MEDIA: {"status": 200, "data": f'\\"{OP}\\",null,null,\\"{VIDEO_MEDIA}\\"'},
            fb.RPC_MEDIA: lambda _f: {"status": 200, "data": next(media_answers)},
        })
        mock.patch.object(fx.requests, "get", return_value=FakeResponse()).start()
        res = fx.FlowExtensionDriver().generate(self.job("VIDEO", [self.ref], durationSeconds=7, aspectRatio="9:16"),
                                                lambda: False)
        self.assertEqual(res.staged_file.name, "X-1.mp4")
        self.assertEqual(res.remote_ref, VIDEO_MEDIA)
        gen = next(c for c in self.calls if c[0] == fb.RPC_GEN_VIDEO_REFERENCES)
        self.assertIn("abra_r2v_8s", gen[1])           # 7 giây -> gần nhất 8
        self.assertEqual(gen[2], fb.CAPTCHA_VIDEO)
        listing = next(c for c in self.calls if c[0] == fb.RPC_PROJECT_MEDIA)
        self.assertEqual(listing[3], OP)

    def test_video_not_found_while_rendering_keeps_polling_without_resubmit(self):
        """as29s trả [5] (video còn đang tạo): chờ tiếp, KHÔNG upload lại ảnh và KHÔNG gửi lại lệnh tạo."""
        video_url = f"https://flow-content.google/video/{VIDEO_MEDIA}?sig=5"
        not_found = ")]}'\n" + json.dumps([["wrb.fr", fb.RPC_MEDIA, None, None, None, [5], "generic"]])
        media_answers = iter([not_found, not_found, envelope(fb.RPC_MEDIA, [[video_url]])])
        self.fake_rpc({
            fb.RPC_UPLOAD_IMAGE: {"status": 200, "data": envelope(fb.RPC_UPLOAD_IMAGE, [["ref-media-1"]])},
            fb.RPC_GEN_VIDEO_REFERENCES: {"status": 200, "data": envelope(fb.RPC_GEN_VIDEO_REFERENCES, [None, 50, [[OP, PROJECT, "scene", None]]])},
            fb.RPC_OPERATION: {"status": 200, "data": envelope(fb.RPC_OPERATION, [None, 50, [[OP, PROJECT, "scene", "CAE"]]])},
            fb.RPC_PROJECT_MEDIA: {"status": 200, "data": f'\\"{OP}\\",null,null,\\"{VIDEO_MEDIA}\\"'},
            fb.RPC_MEDIA: lambda _f: {"status": 200, "data": next(media_answers)},
        })
        mock.patch.object(fx.requests, "get", return_value=FakeResponse()).start()
        res = fx.FlowExtensionDriver().generate(self.job("VIDEO", [self.ref]), lambda: False)
        self.assertEqual(res.remote_ref, VIDEO_MEDIA)
        self.assertEqual(sum(1 for c in self.calls if c[0] == fb.RPC_GEN_VIDEO_REFERENCES), 1)
        self.assertEqual(sum(1 for c in self.calls if c[0] == fb.RPC_UPLOAD_IMAGE), 1)

    def test_video_without_references_uses_text_to_video(self):
        video_url = f"https://flow-content.google/video/{VIDEO_MEDIA}?sig=3"
        self.fake_rpc({
            fb.RPC_GEN_VIDEO_TEXT: {"status": 200, "data": envelope(fb.RPC_GEN_VIDEO_TEXT, [None, None, None, [[VIDEO_MEDIA, PROJECT, "wf", None]]])},
            fb.RPC_MEDIA: {"status": 200, "data": envelope(fb.RPC_MEDIA, [[video_url]])},
        })
        mock.patch.object(fx.requests, "get", return_value=FakeResponse()).start()
        res = fx.FlowExtensionDriver().generate(self.job("VIDEO"), lambda: False)
        self.assertEqual(res.remote_ref, VIDEO_MEDIA)
        self.assertIn("abra_t2v_8s", next(c for c in self.calls if c[0] == fb.RPC_GEN_VIDEO_TEXT)[1])

    def test_creates_and_remembers_project_when_film_has_no_flow_link(self):
        self.fake_rpc({
            fb.RPC_CREATE_PROJECT: {"status": 200, "data": envelope(fb.RPC_CREATE_PROJECT, [PROJECT, ["Flow Batch Studio"]])},
        })
        d = fx.FlowExtensionDriver()
        self.assertEqual(d._project_id(self.job("IMAGE", flowProjectUrl="")), PROJECT)
        self.assertEqual(d._project_id(self.job("IMAGE", flowProjectUrl="")), PROJECT)
        self.assertEqual(sum(1 for c in self.calls if c[0] == fb.RPC_CREATE_PROJECT), 1)

    def test_too_many_references_rejected(self):
        refs = []
        for i in range(8):
            p = self.dir / f"R-{i}.png"
            p.write_bytes(b"\x89PNG" + bytes([i]) * 100)
            refs.append(p)
        self.fake_rpc({fb.RPC_UPLOAD_IMAGE: lambda f: {"status": 200, "data": envelope(fb.RPC_UPLOAD_IMAGE, [[f"m{len(self.calls)}"]])}})
        with self.assertRaises(Rejected):
            fx.FlowExtensionDriver().generate(self.job("VIDEO", refs), lambda: False)

    def test_no_extension_waits_then_is_transient(self):
        self.connected.return_value = False
        self.fake_rpc({})
        with mock.patch.object(fx, "EXTENSION_WAIT_S", 0), self.assertRaises(TransientError) as e:
            fx.FlowExtensionDriver().generate(self.job("VIDEO"), lambda: False)
        self.assertIn("extension", str(e.exception))
        self.assertEqual(self.calls, [])

    def test_resends_after_extension_reconnects(self):
        video_url = f"https://flow-content.google/video/{VIDEO_MEDIA}?sig=4"
        answers = iter([{"error": "NO_EXTENSION"},
                        {"status": 200, "data": envelope(fb.RPC_GEN_VIDEO_TEXT, [None, None, None, [[VIDEO_MEDIA, PROJECT, "wf", None]]])}])
        self.fake_rpc({fb.RPC_GEN_VIDEO_TEXT: lambda _f: next(answers),
                       fb.RPC_MEDIA: {"status": 200, "data": envelope(fb.RPC_MEDIA, [[video_url]])}})
        mock.patch.object(fx.requests, "get", return_value=FakeResponse()).start()
        res = fx.FlowExtensionDriver().generate(self.job("VIDEO"), lambda: False)
        self.assertEqual(res.remote_ref, VIDEO_MEDIA)
        self.assertEqual(sum(1 for c in self.calls if c[0] == fb.RPC_GEN_VIDEO_TEXT), 2)

    def test_error_classification(self):
        self.assertIsInstance(fx.classify("PUBLIC_ERROR_UNUSUAL_ACTIVITY"), BotSuspected)
        self.assertIsInstance(fx.classify("FLOW_HOLD: x"), BotSuspected)
        self.assertIsInstance(fx.classify("PUBLIC_ERROR_USER_QUOTA_REACHED"), QuotaExceeded)
        self.assertIsInstance(fx.classify("PUBLIC_ERROR_UNSAFE_GENERATION"), Rejected)
        self.assertIsInstance(fx.classify("NO_AT_TOKEN"), AuthRequired)
        self.assertIsInstance(fx.classify("something else"), TransientError)

    def test_duration_snaps_to_omni_values(self):
        self.assertEqual(fx.FlowExtensionDriver._duration({"durationSeconds": 5}), 6)
        self.assertEqual(fx.FlowExtensionDriver._duration({"durationSeconds": 15}), 10)
        self.assertEqual(fx.FlowExtensionDriver._duration({}), 8)


if __name__ == "__main__":
    unittest.main()
