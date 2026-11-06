"""
Bố cục như ảnh chụp 2026-11-06: nút ⚙ trên thanh trên cùng (cài đặt dự án, KHÔNG được bấm) và chip cạnh ô prompt
"🍌 Nano Banana 2.1 ▭ x2" mở bảng cài đặt. App phải bấm chip, chọn model, sửa mục sai (x2 → x1, High → Low), rồi đọc chip
thấy đã đúng. Bỏ qua nếu máy không mở được Edge.
"""
import sys
import unittest
from pathlib import Path

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

from drivers import flow_browser as fb  # noqa: E402

HTML = """<html><body>
<button aria-label="Cài đặt" id="gear" onclick="window.gearClicked = true"><mat-icon>settings</mat-icon></button>
<div class="prompt-bar">
  <div role="textbox" contenteditable="true"></div>
  <button id="chip" onclick="document.querySelector('.cdk-overlay-container').style.display='block'">
    <span id="chipModel">🍌 Nano Banana 2.1</span><mat-icon id="chipRatio">crop_16_9</mat-icon><span id="chipCount"> x2 </span></button>
</div>
<div class="cdk-overlay-container" style="display:none">
  <button role="tab" class="g1" aria-selected="true">image Hình ảnh</button><button role="tab" class="g1" aria-selected="false">videocam Video</button>
  <button role="radio" class="g2" aria-checked="true" data-r="16_9">crop_16_9 16:9</button>
  <button role="radio" class="g2" aria-checked="false" data-r="9_16">crop_9_16 9:16</button>
  <button aria-haspopup="listbox" id="model" onclick="document.getElementById('list').style.display='block'">Nano Banana Pro arrow_drop_down</button>
  <div id="list" role="listbox" style="display:none">
    <div role="option" id="opt21">Nano Banana 2.1</div>
  </div>
  <button role="radio" class="g3" aria-checked="false">Low</button><button role="radio" class="g3" aria-checked="false">Medium</button>
  <button role="radio" class="g3" aria-checked="true">High</button>
  <button role="radio" class="g4" aria-checked="false" data-n="1">x1</button><button role="radio" class="g4" aria-checked="true" data-n="2">x2</button>
</div>
<script>
window.clicked = [];
document.getElementById('opt21').onclick = () => { document.getElementById('model').innerText = 'Nano Banana 2.1 arrow_drop_down';
  document.getElementById('chipModel').innerText = '🍌 Nano Banana 2.1'; document.getElementById('list').style.display = 'none'; };
document.addEventListener('click', e => {
  const b = e.target.closest('[role=tab],[role=radio],[role=option]'); if (!b) return;
  window.clicked.push(b.innerText.trim());
  const g = [...b.classList].find(c => /^g\\d/.test(c));
  if (g) document.querySelectorAll('.' + g).forEach(x => x.setAttribute(x.getAttribute('role') === 'tab' ? 'aria-selected' : 'aria-checked', String(x === b)));
  if (b.dataset.n) document.getElementById('chipCount').innerText = ' x' + b.dataset.n + ' ';
  if (b.dataset.r) document.getElementById('chipRatio').innerText = 'crop_' + b.dataset.r;
}, true);
document.addEventListener('keydown', e => { if (e.key === 'Escape') document.querySelector('.cdk-overlay-container').style.display = 'none'; });
</script></body></html>"""

JOB = {"aspectRatio": "16:9", "flowModel": "Nano Banana 2.1", "flowThinking": "low", "humanPace": 0}
S = {"settings_button": "[aria-label='Settings trigger'], [aria-label='Cài đặt']"}


class ChipDomTest(unittest.TestCase):
    def test_chip_opens_settings_not_gear_and_fixes_only_wrong_options(self):
        try:
            from playwright.sync_api import sync_playwright
            pw = sync_playwright().start()
            browser = pw.chromium.launch(channel="msedge")
        except Exception as e:  # noqa: BLE001
            self.skipTest(f"không mở được Edge: {e}")
        try:
            page = browser.new_page()
            page.set_content(HTML)
            self.assertFalse(fb.FlowBrowserDriver._chip_ok(page, S, "IMAGE", JOB))   # chip đang x2
            fixed = fb.FlowBrowserDriver._apply_settings(page, S, "IMAGE", JOB)
            self.assertIsNone(page.evaluate("window.gearClicked || null"), "không được bấm nút ⚙ cài đặt dự án")
            self.assertEqual(page.evaluate("window.clicked"), ["Nano Banana 2.1", "Low", "x1"])   # model trước
            self.assertEqual(fixed, ["model Nano Banana 2.1", "thinking low", "x1"])
            self.assertTrue(fb.FlowBrowserDriver._chip_ok(page, S, "IMAGE", JOB))    # chip giờ: Nano Banana 2.1, 16:9, x1
            # model sai trên chip -> không tin chip
            self.assertFalse(fb.FlowBrowserDriver._chip_ok(page, S, "IMAGE", dict(JOB, flowModel="Imagen 4")))
            self.assertFalse(fb.FlowBrowserDriver._chip_ok(page, S, "VIDEO", JOB))
        finally:
            browser.close()
            pw.stop()


class ChipStateTest(unittest.TestCase):
    def test_parse(self):
        st = fb.chip_state("🍌 Nano Banana 2.1 crop_16_9 x2")
        self.assertEqual((st["ratio"], st["count"], st["model"]), ("16:9", 2, "Nano Banana 2.1"))
        self.assertEqual(fb.chip_state("Veo 3.1 - Fast\ncrop_9_16\nx1")["ratio"], "9:16")


if __name__ == "__main__":
    unittest.main()
