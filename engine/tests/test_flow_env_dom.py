"""Bảng cài đặt Flow trên DOM thật (Edge): mục đã đúng thì không bấm, chỉ sửa mục sai. Bỏ qua nếu máy không mở được Edge."""
import sys
import unittest
from pathlib import Path

ENGINE = Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

from drivers import flow_browser as fb  # noqa: E402

HTML = """<html><body>
<button aria-label="Cài đặt" onclick="document.querySelector('.cdk-overlay-container').style.display='block'">Nano Banana 2.1 x2</button>
<div class="cdk-overlay-container" style="display:none">
  <div role="tablist"><button role="tab" class="g1" aria-selected="true">image Hình ảnh</button><button role="tab" class="g1" aria-selected="false">videocam Video</button></div>
  <button role="radio" class="g2" aria-checked="false">crop_16_9 16:9</button><button role="radio" class="g2" aria-checked="true">crop_9_16 9:16</button>
  <button aria-haspopup="listbox" id="model" onclick="document.getElementById('list').style.display='block'">Nano Banana Pro arrow_drop_down</button>
  <div id="list" role="listbox" style="display:none">
    <div role="option" onclick="document.getElementById('model').innerText='Nano Banana 2.1'; this.parentNode.style.display='none'">Nano Banana 2.1</div>
  </div>
  <button role="radio" class="g3" aria-checked="false">Low</button><button role="radio" class="g3" aria-checked="true">Medium</button><button role="radio" class="g3" aria-checked="false">High</button>
  <button role="radio" class="g4" aria-checked="true">x1</button><button role="radio" class="g4" aria-checked="false">x2</button>
</div>
<script>
window.clicked = [];
document.addEventListener('click', e => {
  const b = e.target.closest('[role=tab],[role=radio],[role=option]'); if (!b) return;
  window.clicked.push(b.innerText.trim());
  const g = [...b.classList].find(c => /^g\\d/.test(c));
  if (g) document.querySelectorAll('.' + g).forEach(x => x.setAttribute(x.getAttribute('role') === 'tab' ? 'aria-selected' : 'aria-checked', String(x === b)));
}, true);
</script></body></html>"""

JOB = {"aspectRatio": "16:9", "flowModel": "Nano Banana 2.1", "flowThinking": "low", "humanPace": 0}
S = {"settings_button": "[aria-label='Cài đặt']"}


class EnvDomTest(unittest.TestCase):
    def test_only_wrong_options_are_clicked_and_second_check_clicks_nothing(self):
        try:
            from playwright.sync_api import sync_playwright
            pw = sync_playwright().start()
            browser = pw.chromium.launch(channel="msedge")
        except Exception as e:  # noqa: BLE001
            self.skipTest(f"không mở được Edge: {e}")
        try:
            page = browser.new_page()
            page.set_content(HTML)
            fixed = fb.FlowBrowserDriver._apply_settings(page, S, "IMAGE", JOB)
            self.assertEqual(page.evaluate("window.clicked"), ["Nano Banana 2.1", "crop_16_9 16:9", "Low"])
            self.assertEqual(len(fixed), 3)
            page.evaluate("window.clicked = []; document.querySelector('.cdk-overlay-container').style.display='none'")
            self.assertEqual(fb.FlowBrowserDriver._apply_settings(page, S, "IMAGE", JOB), [])
            self.assertEqual(page.evaluate("window.clicked"), [])
        finally:
            browser.close()
            pw.stop()


if __name__ == "__main__":
    unittest.main()
