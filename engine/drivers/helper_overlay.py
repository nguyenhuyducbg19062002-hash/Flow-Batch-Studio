"""
Hỗ trợ chế độ FOREGROUND ("Mở Flow"): người dùng tự thao tác trên Flow, app chỉ hướng dẫn và bắt file tải về.

- prepare_refs_folder(): chép ảnh reference của cảnh vào một thư mục riêng, đánh số theo đúng thứ tự gắn ảnh
  (01_PANEL-1.png, 02_CHAR-CALEB-1.png…) để người dùng kéo thả vào Flow; mở thư mục đó trong Explorer.
- overlay_script(): đoạn JavaScript chèn vào trang Flow một hộp hướng dẫn nổi (Shadow DOM, không đụng tới CSS của Flow):
  cảnh nào, ảnh nào theo thứ tự, nút Copy prompt, các bước cần làm.
"""
import json
import shutil
import subprocess
from pathlib import Path

from .errors import TransientError


def prepare_refs_folder(job: dict) -> Path | None:
    images = job.get("referenceImages") or []
    if not images:
        return None
    folder = Path(job["stagingDir"]) / "anh-can-gan"
    if folder.exists():
        shutil.rmtree(folder, ignore_errors=True)
    folder.mkdir(parents=True, exist_ok=True)
    for i, src in enumerate(images, start=1):
        p = Path(src)
        if p.is_file():
            try:
                shutil.copy2(p, folder / f"{i:02d}_{p.name}")
            except OSError as e:
                raise TransientError(f"Không chép được ảnh reference {p.name} vào thư mục ảnh cần gắn: {e}") from e
    return folder


def open_in_explorer(folder: Path):
    try:
        subprocess.Popen(["explorer", str(folder)])
    except OSError:
        pass


def overlay_script(job: dict, refs_folder: Path | None) -> str:
    refs = [Path(p).stem for p in job.get("referenceImages") or []]
    title = (f"Video STT {job['externalId']}" if job["kind"] == "VIDEO" else f"Ảnh {job['externalId']}")
    data = {
        "title": title,
        "kind": job["kind"],
        "refs": refs,
        "refsFolder": str(refs_folder) if refs_folder else "",
        "prompt": job.get("prompt") or "",
        "duration": job.get("durationSeconds"),
        "quality": job.get("videoQuality"),
    }
    return "window.__afmData = " + json.dumps(data, ensure_ascii=False) + ";\n" + _OVERLAY_JS


_OVERLAY_JS = r"""
(() => {
  if (window.top !== window) return;
  const d = window.__afmData;
  if (!d) return;
  const el = (tag, css, text) => { const e = document.createElement(tag); if (css) e.style.cssText = css; if (text != null) e.textContent = text; return e; };

  const mount = () => {
    if (document.getElementById('afm-helper')) return;
    // góc dưới bên TRÁI: ô prompt của Flow ở giữa (tạo trực tiếp) hoặc bên phải (Tác nhân), đừng che
    const host = el('div', 'position:fixed;left:12px;bottom:12px;z-index:2147483647;');
    host.id = 'afm-helper';
    const root = host.attachShadow({ mode: 'open' });
    const box = el('div', 'width:330px;max-height:62vh;overflow:auto;background:#fff;color:#1e293b;border-radius:14px;' +
      'box-shadow:0 12px 40px rgba(0,0,0,.35);font:13px/1.45 Segoe UI,Inter,sans-serif;border:2px solid #6366f1;');
    const head = el('div', 'display:flex;align-items:center;gap:8px;padding:10px 12px;background:#4f46e5;color:#fff;border-radius:12px 12px 0 0;cursor:pointer;');
    head.append(el('b', 'flex:1', 'Flow Batch Studio · ' + d.title), el('span', 'opacity:.85;font-size:12px', 'thu gọn ▾'));
    const body = el('div', 'padding:12px;');

    // Dòng trạng thái: driver gọi window.__afmStatus("Đang tải ảnh…") để người dùng biết app đang tự điền
    const status = el('div', 'margin:0 0 10px;padding:8px 10px;border-radius:8px;background:#fef3c7;color:#92400e;font-weight:600;',
      window.__afmLastStatus || 'App đang mở dự án Flow…');
    window.__afmStatus = (t) => {
      window.__afmLastStatus = t;
      status.textContent = t;
      const done = /^✓/.test(t);
      status.style.background = done ? '#dcfce7' : '#fef3c7';
      status.style.color = done ? '#166534' : '#92400e';
    };
    window.__afmUpdate = (newData) => {
      Object.assign(d, newData);
      const b = head.querySelector('b');
      if (b) b.textContent = 'Flow Batch Studio · ' + (newData.title || '');
      if (ta) ta.value = newData.prompt || '';
      if (newData.title) window.__afmStatus('App đang tự điền cho ' + newData.title + '…');
    };
    body.append(status);
    const steps = el('ol', 'margin:0 0 10px 18px;padding:0;');
    const add = (t) => steps.append(el('li', 'margin:2px 0', t));
    add('App tự mở dự án, tự tải ' + (d.refs.length ? d.refs.length + ' ảnh lên Flow và ' : '') + 'gõ prompt có sẵn @tag (chip ảnh) vào ô chat.');
    add('BẠN TỰ BẤM GỬI: Sau khi app điền xong prompt và gắn @tag, bạn hãy kiểm tra lại rồi bấm nút Tạo (hoặc Enter) khi sẵn sàng.');
    add('Khi Flow tạo xong: bạn bấm Tải xuống (hoặc app sẽ tự bắt file tải về), lưu đúng ID cảnh và chuyển sang cảnh kế tiếp.');
    if (d.refs.length) add('Nếu app không tự gắn được ảnh (thư mục ảnh sẽ tự mở): kéo ảnh theo ĐÚNG thứ tự bên dưới, rồi dùng "Copy prompt".');
    body.append(steps);

    if (d.refs.length) {
      body.append(el('div', 'font-weight:600;margin:6px 0 4px', 'Ảnh cần gắn (theo thứ tự):'));
      const list = el('div', 'display:flex;flex-wrap:wrap;gap:4px;margin-bottom:6px;');
      d.refs.forEach((r, i) => list.append(el('span', 'background:#eef2ff;color:#3730a3;border-radius:6px;padding:2px 6px;font:12px Consolas,monospace', (i + 1) + '. ' + r)));
      body.append(list);
      if (d.refsFolder) body.append(el('div', 'font-size:11px;color:#64748b;word-break:break-all;margin-bottom:8px', 'Thư mục ảnh: ' + d.refsFolder));
    }

    const ta = el('textarea', 'width:100%;box-sizing:border-box;height:90px;font:11px Consolas,monospace;border:1px solid #cbd5e1;border-radius:8px;padding:6px;');
    ta.readOnly = true;
    ta.value = d.prompt;
    const copy = el('button', 'margin-top:6px;width:100%;padding:8px;border:0;border-radius:8px;background:#4f46e5;color:#fff;font-weight:600;cursor:pointer;', 'Copy prompt');
    copy.onclick = async () => {
      try { await navigator.clipboard.writeText(d.prompt); }
      catch { ta.focus(); ta.select(); document.execCommand('copy'); }
      copy.textContent = 'Đã copy ✓ — dán vào ô prompt của Flow';
      setTimeout(() => { copy.textContent = 'Copy prompt'; }, 3000);
    };
    // Hai nút gọi về app (Playwright binding window.afmAction)
    const row = el('div', 'display:flex;gap:6px;margin-top:8px;');
    const act = (label, action, css, confirmText) => {
      const b = el('button', 'flex:1;padding:7px;border-radius:8px;font-weight:600;cursor:pointer;' + css, label);
      b.onclick = () => {
        if (confirmText && !window.confirm(confirmText)) return;
        b.textContent = 'Đang gửi…';
        if (window.afmAction) window.afmAction(action);
      };
      return b;
    };
    row.append(
      act('🚀 Gửi prompt (Tạo)', 'generate_now', 'border:1px solid #4f46e5;background:#4f46e5;color:#fff;', null),
      act('⏸ Tạm dừng', 'pause', 'border:1px solid #f59e0b;background:#fffbeb;color:#b45309;', null),
      act('Bỏ qua', 'skip', 'border:1px solid #cbd5e1;background:#fff;color:#475569;', 'Bỏ qua cảnh này?'));
    const row2 = el('div', 'display:flex;gap:6px;margin-top:6px;');
    row2.append(act('Tài khoản hết lượt → đổi tài khoản', 'quota', 'border:1px solid #fca5a5;background:#fef2f2;color:#b91c1c;',
      'Đánh dấu tài khoản này hết lượt (nghỉ vài giờ) và làm cảnh này trên tài khoản khác?'));
    body.append(ta, copy, row, row2,
      el('div', 'font-size:11px;color:#64748b;margin-top:8px', 'Đóng cửa sổ này = tạm dừng phim; cảnh này quay về "Chờ chạy". Chọn lại cảnh ở trang Danh sách cảnh.'));

    head.onclick = () => {
      const hidden = body.style.display === 'none';
      body.style.display = hidden ? '' : 'none';
      head.lastChild.textContent = hidden ? 'thu gọn ▾' : 'mở ▴';
    };
    box.append(head, body);
    root.append(box);
    document.documentElement.append(host);
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
  setInterval(mount, 2000);   // trang Flow là SPA: nếu hộp bị xóa khi đổi màn hình thì gắn lại
})();
"""
