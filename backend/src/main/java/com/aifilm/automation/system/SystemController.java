package com.aifilm.automation.system;

import com.aifilm.automation.worker.PythonWorkerSupervisor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final PythonWorkerSupervisor supervisor;
    private final AppShutdown appShutdown;
    private final DesktopWindow desktopWindow;

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataDir", AppPaths.data().toString());
        m.put("homeDir", AppPaths.home().toString());
        m.put("logsDir", AppPaths.logs().toString());
        m.put("workersAlive", supervisor.aliveCount());
        m.put("javaVersion", System.getProperty("java.version"));
        return m;
    }

    /** Nút "Thoát" trên giao diện. Tắt cả worker và Edge con. */
    @PostMapping("/shutdown")
    public Map<String, String> shutdown() {
        appShutdown.exitSoon("người dùng bấm Thoát");
        return Map.of("message", "Đang tắt ứng dụng");
    }

    /** Cửa sổ ứng dụng còn mở (giao diện gửi mỗi vài giây). */
    @PostMapping("/ui-heartbeat")
    public void uiHeartbeat() {
        desktopWindow.heartbeat();
    }

    /** Cửa sổ ứng dụng vừa đóng hoặc tải lại (navigator.sendBeacon lúc pagehide). */
    @PostMapping("/ui-closed")
    public void uiClosed() {
        desktopWindow.uiClosed();
    }

    /**
     * Mở hộp thoại chọn thư mục của Windows (trình duyệt không cho web đọc đường dẫn thật trên máy).
     * Trả {"path": null} nếu người dùng bấm Hủy.
     */
    @PostMapping("/pick-folder")
    public Map<String, String> pickFolder(@RequestBody(required = false) Map<String, String> body) throws Exception {
        String initial = body == null ? "" : body.getOrDefault("initial", "");
        String script = """
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -Namespace Afm -Name Win -MemberDefinition @'
                [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
                [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
                '@
                # Cửa sổ chủ nhỏ, nằm ngoài màn hình, luôn trên cùng và ĐƯỢC KÍCH HOẠT: hộp chọn thư mục thuộc về nó nên hiện trên cùng,
                # không bị cửa sổ ứng dụng che (tiến trình PowerShell nền mặc định không được giành tiêu điểm)
                $owner = New-Object System.Windows.Forms.Form -Property @{TopMost = $true; ShowInTaskbar = $false; FormBorderStyle = 'None';
                  StartPosition = 'Manual'; Location = (New-Object System.Drawing.Point(-3000, -3000)); Size = (New-Object System.Drawing.Size(1, 1))}
                $owner.Show()
                [Afm.Win]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)      # nhấn/nhả Alt: được phép giành tiêu điểm từ cửa sổ khác
                [Afm.Win]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
                [void][Afm.Win]::SetForegroundWindow($owner.Handle)
                $owner.Activate()
                $d = New-Object System.Windows.Forms.FolderBrowserDialog
                $d.ShowNewFolderButton = $true
                if ($env:AFM_INITIAL -and (Test-Path -LiteralPath $env:AFM_INITIAL)) { $d.SelectedPath = $env:AFM_INITIAL }
                if ($d.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) {
                  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                  [Console]::Out.Write($d.SelectedPath)
                }
                """;
        Path ps1 = Files.createTempFile("afm-pick-", ".ps1");
        try {
            Files.writeString(ps1, "﻿" + script, StandardCharsets.UTF_8);   // BOM: PowerShell 5.1 đọc đúng UTF-8
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-STA", "-ExecutionPolicy", "Bypass", "-File", ps1.toString());
            pb.environment().put("AFM_INITIAL", initial);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
            }
            String path = new String(out, StandardCharsets.UTF_8).trim();
            Map<String, String> m = new LinkedHashMap<>();
            m.put("path", path.isEmpty() ? null : path);
            return m;
        } finally {
            Files.deleteIfExists(ps1);
        }
    }

    /** Mở một thư mục trong Explorer (Folder Ảnh / Folder Video / log). */
    @PostMapping("/open-folder")
    public void openFolder(@RequestBody Map<String, String> body) throws IOException {
        Path p = Path.of(body.get("path"));
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("Thư mục không tồn tại: " + p);
        }
        new ProcessBuilder("explorer.exe", p.toString()).start();
    }
}
