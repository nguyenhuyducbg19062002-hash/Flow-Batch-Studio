package com.aifilm.automation.worker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Chỉ worker có token mới gọi được /api/worker/** (claim job, báo kết quả). */
@Component
@RequiredArgsConstructor
public class WorkerTokenFilter extends OncePerRequestFilter {

    private final WorkerToken token;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/worker/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String got = String.valueOf(req.getHeader("X-Worker-Token"));
        if (!MessageDigest.isEqual(token.value().getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8))) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(req, res);
    }
}
