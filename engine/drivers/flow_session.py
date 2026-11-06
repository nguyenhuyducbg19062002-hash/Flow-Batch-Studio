"""
Phiên Flow chạy SONG SONG nhiều lệnh trên một tài khoản (một lần mở trình duyệt): trong lúc lệnh A còn đang tạo thì gửi tiếp B, C…;
lệnh nào xong trước thì tải về và báo ngay (chuyển "Chờ duyệt"), không đợi các lệnh khác. Xem docs/SONG-SONG.md.

File này chỉ chứa LOGIC (không dính Playwright) để test được bằng một trang Flow giả. FlowBrowserDriver.run_session dựng
FlowOps thật (đọc lưới ô, gửi lệnh, tải về) rồi giao cho SessionRunner.

Cách gắn kết quả với đúng lệnh (đã kiểm chứng trên Flow thật): dấu gắn lên ô KHÔNG bền (Flow vẽ lại ô), nhưng thứ tự ô thì ổn
định — ô mới nhất ở ĐẦU danh sách. Lệnh gửi thứ k (rank = k) khi đã gửi tổng cộng S lệnh là ô thứ (S − 1 − k) TÍNH TỪ TRÊN CÙNG.

Lưới Flow dùng cuộn ảo (cdk-virtual-scrollable): chỉ khoảng 16 ô nằm trong trang, ô cũ ở cuối bị ẩn. Vì vậy KHÔNG được dựa vào
tổng số ô (dự án nhiều ô thì số ô không tăng khi gửi lệnh mới → tưởng chưa gửi được → gửi trùng, tốn credit). Chỉ dùng vị trí từ
trên xuống, và FlowOps.generated_tiles phải đưa lưới về đầu trước khi đọc.

Phát hiện lệch pha (ai đó tạo / xóa ô bằng tay): lúc bắt đầu ghi lại khóa của ô mới nhất đang có ("mỏ neo"). Nếu mỏ neo còn trong
cửa sổ thì nó phải nằm đúng vị trí S (có đúng S ô mới hơn nó); sai quá desync_polls lần đọc liên tiếp thì dừng phiên, các lệnh chưa
xong báo lỗi tạm thời để thử lại. Mỏ neo bị cuộn ảo ẩn đi (phiên dài) hoặc không có khóa ổn định thì bỏ qua kiểm tra này.
"""
import logging
import time
from dataclasses import dataclass
from typing import Callable, Optional, Protocol

from .errors import (AccountBroken, AuthRequired, BotSuspected, Cancelled, ConfigError, GenerationResult, PausedByUser, QuotaExceeded,
                     Rejected, TransientError, WorkerError)

log = logging.getLogger("flow.session")


@dataclass
class Tile:
    key: Optional[str]    # media id (ảnh) / khóa băm ảnh thu nhỏ (video); None khi chưa xong
    label: str
    pct: Optional[int]    # % đang tạo; None khi không còn chạy
    ready: bool           # đã xong, tải về được
    failed: bool = False  # Flow đánh dấu ô "Failed" (không tính phí): không bao giờ xong, phải báo lỗi ngay thay vì chờ hết giờ
    error: str = ""       # chữ Flow ghi trên ô lỗi


class FlowOps(Protocol):
    def generated_tiles(self) -> list[Tile]:
        """Cửa sổ các ô do Flow TẠO RA (bỏ ô ảnh người dùng tải lên), mới nhất ở đầu. Phải đưa lưới về đầu trước khi đọc."""

    def submit(self, job: dict) -> None:
        """Tải ảnh tham chiếu còn thiếu, gõ prompt, bấm Tạo; chỉ trả về khi chắc chắn một ô MỚI đã xuất hiện ở trên cùng
        (không dựa vào tổng số ô). Ném WorkerError nếu thất bại."""

    def download(self, job: dict, tile: Tile) -> GenerationResult:
        """Tải kết quả của ô về thư mục staging của job."""

    def page_error(self) -> Optional[tuple]:
        """("quota" | "policy", chữ đang hiện) nếu trang đang báo hết lượt / vi phạm chính sách, ngược lại None."""

    def ensure_grid(self) -> None:
        """Đang ở màn chỉnh sửa (Flow tự mở sau khi tạo video) thì quay lại lưới ô của dự án."""


@dataclass
class Slot:
    job: dict
    rank: int
    submitted_at: float


# Lỗi làm cả phiên vô dụng (tài khoản hỏng / cấu hình sai): báo đúng lỗi đó cho MỌI lệnh đang chạy để backend xử lý từng job
_FATAL = (AuthRequired, AccountBroken, ConfigError, PausedByUser)


class SessionRunner:
    def __init__(self, ops: FlowOps, capacity: int, claim_more: Callable[[], Optional[dict]],
                 on_start: Callable[[dict], None], on_done: Callable[[dict, GenerationResult], None],
                 on_fail: Callable[[dict, WorkerError], None], stop_of: Callable[[dict], Callable[[], bool]],
                 poll_s: float = 1.5, gap_s: float = 2.0, claim_every_s: float = 3.0,
                 desync_polls: int = 3, clock=time.monotonic, sleep=time.sleep):
        self.ops, self.capacity = ops, max(1, capacity)
        self.claim_more, self.on_start, self.on_done, self.on_fail, self.stop_of = claim_more, on_start, on_done, on_fail, stop_of
        self.poll_s, self.gap_s, self.claim_every_s, self.desync_polls = poll_s, gap_s, claim_every_s, desync_polls
        self.clock, self.sleep = clock, sleep
        self.slots: list[Slot] = []
        self.submitted = 0
        self.anchor: Optional[str] = None   # khóa ô mới nhất lúc bắt đầu phiên (để phát hiện lệch pha)
        self._claiming = True
        self._next_claim_at = 0.0
        self._mismatch = 0
        self._handled_error: Optional[tuple] = None
        self._pending: list = []

    # ---------- vòng chính ----------
    def run(self, first_job: dict) -> None:
        self._pending = [first_job]   # đã nhận từ backend nhưng chưa gửi lên Flow
        self._started_at = self.clock()
        try:
            first = self.ops.generated_tiles()
            self.anchor = first[0].key if first else None
            while True:
                self._fill(self._pending)
                if not self.slots:
                    break
                if not self._poll():
                    break
                self.sleep(self.poll_s)
        except Exception as e:   # trình duyệt chết / lỗi không lường trước: không để job nào treo ở GENERATING
            log.exception("Phiên Flow lỗi")
            self._fail_all(e if isinstance(e, WorkerError) else TransientError(f"{type(e).__name__}: {e}"[:500]))
        # còn job nhận rồi mà chưa gửi được (lệch pha, lỗi nặng…): trả lại cho backend, không để treo
        while self._pending:
            self._finish(self._pending.pop(0), TransientError("Phiên Flow kết thúc trước khi gửi lệnh này"))

    # ---------- gửi lệnh ----------
    def _fill(self, pending: list) -> None:
        while self._claiming and len(self.slots) < self.capacity:
            job = None
            if pending:
                job = pending.pop(0)
            elif self.clock() >= self._next_claim_at:
                job = self.claim_more()
                if job is None:
                    self._next_claim_at = self.clock() + self.claim_every_s
                    return
            else:
                return
            self.on_start(job)
            try:
                self.ops.submit(job)
            except (QuotaExceeded, BotSuspected) as e:
                self._claiming = False
                if isinstance(e, BotSuspected):
                    e = BotSuspected(f"{e}{self._since_start()}")
                self._finish(job, e)
                return
            except _FATAL as e:
                self._claiming = False
                self._finish(job, e)
                self._fail_all(e)
                return
            except WorkerError as e:   # prompt bị từ chối, lỗi tạm thời…: chỉ riêng lệnh này
                self._finish(job, e)
                continue
            except Exception as e:
                self._finish(job, TransientError(f"{type(e).__name__}: {e}"[:500]))
                continue
            self.slots.append(Slot(job, self.submitted, self.clock()))
            self.submitted += 1
            self._handled_error = None
            if self.gap_s and len(self.slots) < self.capacity:
                self.sleep(self.gap_s)   # giãn nhịp giữa các lần gửi

    # ---------- theo dõi kết quả ----------
    def _poll(self) -> bool:
        """False = phiên phải dừng (lệch pha)."""
        self.ops.ensure_grid()
        tiles = self.ops.generated_tiles()
        s = self.submitted

        # lệch pha: mỏ neo còn trong cửa sổ thì phải có đúng s ô mới hơn nó
        pos = next((i for i, t in enumerate(tiles) if self.anchor is not None and t.key == self.anchor), None)
        if pos is not None and pos != s:
            self._mismatch += 1
            if self._mismatch >= self.desync_polls:
                log.warning("Phiên Flow lệch pha: có %d ô mới hơn mỏ neo, dự kiến %d", pos, s)
                self._claiming = False
                self._fail_all(TransientError(
                    f"Lưới Flow lệch so với phiên ({pos} ô mới thay vì {s}): có ai tạo / xóa ô bằng tay? Sẽ thử lại"))
                return False
            return True
        self._mismatch = 0

        for slot in list(self.slots):
            job = slot.job
            if self.stop_of(job)():
                self._finish(job, Cancelled("Người dùng hủy job"), slot)
                continue
            idx = s - 1 - slot.rank   # vị trí từ trên cùng: bằng số lệnh được gửi SAU lệnh này
            tile = tiles[idx] if 0 <= idx < len(tiles) else None
            if tile is None:
                pass   # ô đang nằm ngoài cửa sổ cuộn ảo (rất cũ) hoặc lưới chưa vẽ xong: chờ tiếp, quá hạn thì báo lỗi bên dưới
            elif tile.failed:
                self._finish(job, self._tile_error(tile.error), slot)
                continue
            elif tile.ready:
                try:
                    result = self.ops.download(job, tile)
                except WorkerError as e:
                    self._finish(job, e, slot)
                except Exception as e:
                    self._finish(job, TransientError(f"{type(e).__name__}: {e}"[:500]), slot)
                else:
                    self._finish(job, None, slot, result)
                continue
            limit = job.get("timeoutSeconds") or 900
            if self.clock() - slot.submitted_at > limit:
                self._finish(job, TransientError(f"Quá {limit}s chưa có kết quả"), slot)

        self._check_page_error()
        return True

    def _since_start(self) -> str:
        """Đo để biết tài khoản làm được bao lâu / bao nhiêu lệnh thì bị Flow báo: ghi vào thông báo lỗi (hiện ở trang Tài khoản)."""
        started = getattr(self, "_started_at", None)
        minutes = (self.clock() - started) / 60 if started is not None else 0.0
        return f" [sau {minutes:.1f} phút, đã gửi {self.submitted} lệnh trong phiên]"

    def _tile_error(self, text: str) -> WorkerError:
        """Ô Flow báo Failed. "unusual activity" = Flow nghi tự động hóa: dừng nhận thêm việc để tài khoản nghỉ, không đốt thêm lệnh."""
        low = (text or "").lower()
        if "unusual activity" in low or "hoạt động bất thường" in low:
            self._claiming = False
            return BotSuspected(f"Flow nghi ngờ hoạt động tự động: {text[:200]}{self._since_start()}")
        if any(w in low for w in ("not enough credits", "out of credits", "hết tín dụng")):
            self._claiming = False
            return QuotaExceeded(f"Flow báo: {text[:200]}")
        if any(w in low for w in ("policy", "policies", "chính sách", "can't generate", "couldn't generate")):
            return Rejected(f"Flow từ chối: {text[:200]}")
        return TransientError(f"Flow báo ô tạo lỗi: {text[:200]}")

    def _check_page_error(self) -> None:
        err = self.ops.page_error()
        if err is None:
            self._handled_error = None
            return
        if err == self._handled_error or not self.slots:
            return
        self._handled_error = err
        kind, text = err
        slot = max(self.slots, key=lambda s: s.rank)   # thông báo hiện ngay sau lần gửi gần nhất
        if kind == "quota":
            self._claiming = False
            self._finish(slot.job, QuotaExceeded(f"Flow báo: {text}"), slot)
        elif kind == "suspicious":
            self._claiming = False   # tài khoản đang bị Flow nghi ngờ: không gửi thêm, backend cho nghỉ và đổi sang tài khoản khác
            self._finish(slot.job, BotSuspected(f"Flow nghi ngờ hoạt động tự động: {text}{self._since_start()}"), slot)
        else:
            self._finish(slot.job, Rejected(f"Flow từ chối: {text}"), slot)

    # ---------- báo kết quả ----------
    def _finish(self, job: dict, err: Optional[WorkerError], slot: Optional[Slot] = None, result=None) -> None:
        if slot is not None and slot in self.slots:
            self.slots.remove(slot)
        if err is None:
            self.on_done(job, result)
        else:
            self.on_fail(job, err)

    def _fail_all(self, err: WorkerError) -> None:
        for slot in list(self.slots):
            self._finish(slot.job, err, slot)
