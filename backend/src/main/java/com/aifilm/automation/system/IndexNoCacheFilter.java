package com.aifilm.automation.system;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Trang chính (index.html) không được lưu cache: nó trỏ tới các file /assets/index-<mã băm>.js. Nếu cửa sổ app giữ
 * index.html cũ (Edge khôi phục phiên trước và dùng lại cache) thì sau khi build lại giao diện vẫn thấy bản cũ.
 * File trong /assets/ có mã băm trong tên nên cache thoải mái.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IndexNoCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if ("/".equals(path) || "/index.html".equals(path)) {
            res.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(req, res);
    }
}
