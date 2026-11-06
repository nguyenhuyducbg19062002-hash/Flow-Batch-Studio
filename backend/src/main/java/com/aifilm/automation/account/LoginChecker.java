package com.aifilm.automation.account;

import com.aifilm.automation.system.AppPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hỏi engine/check_login.py (Playwright + Chrome ẩn, đúng profile của tài khoản) xem tài khoản Google Flow đã đăng nhập chưa.
 * Mất khoảng 1–3 giây. Không chạm DB: gọi ở ngoài transaction.
 */
@Component
@Slf4j
public class LoginChecker {

    /** state: logged_in | logged_out | in_use | error */
    public record Result(String state, String email, String message) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Result check(String profileDir, String proxy) {
        Path home = AppPaths.home();
        Path script = home.resolve("engine").resolve("check_login.py");
        if (!Files.isRegularFile(script)) {
            return new Result("error", "", "Không thấy " + script);
        }
        Path bundled = home.resolve("python").resolve("python.exe");
        String python = Files.exists(bundled) ? bundled.toString() : System.getProperty("afm.python", "python");
        List<String> cmd = new ArrayList<>(List.of(python, "-X", "utf8", script.toString(), profileDir));
        if (proxy != null && !proxy.isBlank()) {
            cmd.add(proxy);
        }
        Path out = null;
        try {
            out = Files.createTempFile("afm-login-check-", ".txt");
            Process p = new ProcessBuilder(cmd).directory(home.toFile()).redirectErrorStream(true)
                    .redirectOutput(out.toFile()).start();
            if (!p.waitFor(75, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Result("error", "", "Quá thời gian chờ kiểm tra đăng nhập (75 giây)");
            }
            return parse(Files.readString(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Result("error", "", "Không chạy được công cụ kiểm tra: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("error", "", "Bị ngắt khi kiểm tra đăng nhập");
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // file tạm: để hệ điều hành dọn
                }
            }
        }
    }

    /** Lấy dòng JSON CUỐI CÙNG của đầu ra (các dòng trước có thể là cảnh báo của Python / Playwright). */
    static Result parse(String output) {
        String[] lines = output == null ? new String[0] : output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                try {
                    JsonNode n = MAPPER.readTree(line);
                    return new Result(n.path("state").asText("error"), n.path("email").asText(""), n.path("message").asText(""));
                } catch (IOException e) {
                    break;
                }
            }
        }
        String tail = output == null ? "" : output.strip();
        log.warn("Đầu ra kiểm tra đăng nhập không đọc được: {}", tail.length() > 300 ? tail.substring(tail.length() - 300) : tail);
        return new Result("error", "", "Công cụ kiểm tra không trả kết quả đọc được");
    }
}
