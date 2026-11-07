"""Nhiều hồ sơ Chrome nối cùng lúc: bridge chuyển lệnh / file theo email từng extension."""
import asyncio
import json
import os
import sys
import time
import unittest
from pathlib import Path
from unittest import mock

ENGINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ENGINE))
os.environ.setdefault("AVS_FLOW_WS_PORT", "18421")
os.environ.setdefault("AVS_FLOW_HTTP_PORT", "18422")
os.environ.setdefault("AVS_FLOW_CDP_PORT", "18423")

from flow_ext import bridge  # noqa: E402


class KeyTests(unittest.TestCase):
    def test_ext_key_is_lowercase_email_or_empty(self):
        self.assertEqual(bridge.ext_key({"email": " A@Gmail.com "}), "a@gmail.com")
        self.assertEqual(bridge.ext_key({"email": "Chrome (Flow Extension)"}), "")
        self.assertEqual(bridge.ext_key(None), "")

    def test_cdp_url_per_account(self):
        self.assertEqual(bridge.cdp_url(""), bridge.CDP_URL)
        self.assertEqual(bridge.cdp_url("B@x.com"), f"{bridge.CDP_URL}/acc/b%40x.com")


class MultiChromeTest(unittest.TestCase):
    def tearDown(self):
        bridge.S.downloads = []   # không để file giả của test này lọt sang test khác
    def test_routes_by_email(self):
        import websockets

        bridge.ensure_bridge()
        got = {"a": [], "b": []}

        async def scenario():
            async def fake_ext(name, email, client_id):
                ws = await websockets.connect(f"ws://127.0.0.1:{bridge.WS_PORT}", origin="chrome-extension://abc")
                await ws.send(json.dumps({"type": "extension_ready", "extensionVersion": "0.4.0", "clientId": client_id,
                                          "email": email}))

                async def serve():
                    async for raw in ws:
                        m = json.loads(raw)
                        got[name].append(m.get("method") or m.get("type"))
                        if m.get("method") == "cdp_attach":
                            await ws.send(json.dumps({"id": m["id"], "result": {"targetInfo": {"targetId": name, "type": "page"}}}))
                        elif m.get("method") in ("cdp", "cdp_detach"):
                            await ws.send(json.dumps({"id": m["id"], "result": {}}))
                return ws, asyncio.create_task(serve())

            a, ta = await fake_ext("a", "A@x.com", "pa")
            b, tb = await fake_ext("b", None, "pb")
            await b.send(json.dumps({"type": "identity", "clientId": "pb", "email": "b@x.com"}))   # đọc được email sau
            for _ in range(50):
                if len([e for e in bridge.S.exts if e.email]) == 2:
                    break
                await asyncio.sleep(0.05)

            st = await asyncio.to_thread(bridge.status, "b@x.com")
            self.assertTrue(st["connected"])
            self.assertEqual(sorted(e["email"] for e in st["extensions"]), ["a@x.com", "b@x.com"])
            self.assertFalse((await asyncio.to_thread(bridge.status, "c@x.com"))["connected"])

            # Playwright của tài khoản b chỉ điều khiển Chrome b; a vẫn rảnh cho cảnh khác
            async with websockets.connect(bridge.cdp_url("b@x.com")) as pw:
                await pw.send(json.dumps({"id": 1, "method": "Target.setAutoAttach", "params": {}}))
                first = json.loads(await asyncio.wait_for(pw.recv(), 5))
                self.assertEqual(first["params"]["targetInfo"]["targetId"], "b")
                self.assertTrue((await asyncio.to_thread(bridge.status, "b@x.com"))["cdpBusy"])
                self.assertFalse((await asyncio.to_thread(bridge.status, "a@x.com"))["cdpBusy"])
                async with websockets.connect(bridge.cdp_url("a@x.com")) as pw2:
                    await pw2.send(json.dumps({"id": 1, "method": "Target.setAutoAttach", "params": {}}))
                    other = json.loads(await asyncio.wait_for(pw2.recv(), 5))
                    self.assertEqual(other["params"]["targetInfo"]["targetId"], "a")
            self.assertIn("cdp_attach", got["a"])
            self.assertEqual(got["b"].count("cdp_attach"), 1)

            # file Chrome a tải chỉ thuộc về tài khoản a
            since = time.time() - 1
            await a.send(json.dumps({"type": "download", "filename": "C:/Downloads/a.png", "mime": "image/png"}))
            for _ in range(50):
                if any(d.get("ext") == "a@x.com" for d in bridge.S.downloads):
                    break
                await asyncio.sleep(0.05)
            self.assertEqual([d["filename"] for d in await asyncio.to_thread(bridge.downloads, since, "a@x.com")],
                             ["C:/Downloads/a.png"])
            self.assertEqual(await asyncio.to_thread(bridge.downloads, since, "b@x.com"), [])

            # extension a nối lại (service worker khởi động lại): kết nối cũ bị thay, không thành hai Chrome
            a2, ta2 = await fake_ext("a", "a@x.com", "pa")
            for _ in range(50):
                if len(bridge.S.exts) == 2 and all(e.ws is not a for e in bridge.S.exts):
                    break
                await asyncio.sleep(0.05)
            self.assertEqual(len(bridge.S.exts), 2)
            for ws, t in ((a, ta), (b, tb), (a2, ta2)):
                t.cancel()
                await ws.close()
            for _ in range(50):
                if not bridge.S.exts:
                    break
                await asyncio.sleep(0.05)

        asyncio.run(scenario())
        self.assertEqual(bridge.S.exts, [])


class WorkerReportTest(unittest.TestCase):
    def test_reports_emails_and_anonymous(self):
        import worker_main
        fake = mock.Mock()
        fake.status.return_value = {"extensions": [{"email": "b@x.com"}, {"email": "a@x.com"}, {"email": None}]}
        with mock.patch.object(worker_main, "call") as call:
            worker_main.report_flow_extensions(fake)
        call.assert_called_once_with("/flow-extensions", {"workerId": worker_main.WORKER_ID,
                                                          "emails": ["a@x.com", "b@x.com"], "anonymous": True})


if __name__ == "__main__":
    unittest.main()
