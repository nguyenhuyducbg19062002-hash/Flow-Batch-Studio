/**
 * Flow Batch Studio - Extension Content Script (ISOLATED World)
 * Chạy trong world riêng của Extension, giám sát DOM và tự động giải quyết các hộp xác minh
 * "Verify it's you" (Cloudflare Turnstile, Google Challenge) với hành vi mô phỏng người dùng.
 */
(function () {
  'use strict';

  let solved = false;
  let solving = false;

  function randomJitter(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
  }

  function simulateHumanClick(element) {
    if (!element || solving || solved) return;
    solving = true;

    const rect = element.getBoundingClientRect();
    const offsetX = Math.floor(rect.width * (0.3 + Math.random() * 0.4));
    const offsetY = Math.floor(rect.height * (0.3 + Math.random() * 0.4));
    const clientX = rect.left + offsetX;
    const clientY = rect.top + offsetY;

    const eventOpts = {
      bubbles: true,
      cancelable: true,
      view: window,
      clientX: clientX,
      clientY: clientY,
      screenX: window.screenX + clientX,
      screenY: window.screenY + clientY
    };

    setTimeout(() => {
      try {
        element.dispatchEvent(new MouseEvent('mouseover', eventOpts));
        element.dispatchEvent(new MouseEvent('mousemove', eventOpts));
        element.dispatchEvent(new MouseEvent('mousedown', eventOpts));
        element.focus();
        setTimeout(() => {
          element.dispatchEvent(new MouseEvent('mouseup', eventOpts));
          element.dispatchEvent(new MouseEvent('click', eventOpts));
          if (typeof element.click === 'function') {
            element.click();
          }
          solved = true;
          solving = false;
          console.info('[AFM Extension] Đã tự động click vượt xác minh Turnstile / Verify it\'s you');
        }, randomJitter(50, 150));
      } catch (err) {
        console.warn('[AFM Extension] Lỗi khi click xác minh:', err);
        solving = false;
      }
    }, randomJitter(600, 1400));
  }

  function checkTurnstileAndVerify() {
    if (solved || solving) return;

    // 1. Kiểm tra iframe Cloudflare Turnstile
    const iframes = document.querySelectorAll('iframe[src*="cloudflare"], iframe[src*="turnstile"], iframe[src*="challenges"]');
    for (const ifr of iframes) {
      try {
        const idoc = ifr.contentDocument || ifr.contentWindow?.document;
        if (idoc) {
          const cb = idoc.querySelector('input[type="checkbox"], .ctp-checkbox-label, #challenge-stage input');
          if (cb && !cb.checked) {
            simulateHumanClick(cb);
            return;
          }
        }
      } catch (e) {
        // Cross-origin iframe: click chính iframe nếu là wrapper turnstile
        simulateHumanClick(ifr);
        return;
      }
    }

    // 2. Kiểm tra container Turnstile trên trang chính
    const turnstileBox = document.querySelector('.cf-turnstile, [data-sitekey], #turnstile-wrapper, [name="cf-turnstile-response"]');
    if (turnstileBox) {
      const cb = turnstileBox.querySelector('input[type="checkbox"], label, button');
      if (cb) {
        simulateHumanClick(cb);
        return;
      }
    }

    // 3. Kiểm tra nút "Verify it's you" hoặc "Xác minh"
    const buttons = document.querySelectorAll('button, a, div[role="button"], input[type="button"], input[type="submit"]');
    for (const btn of buttons) {
      const text = (btn.innerText || btn.textContent || btn.getAttribute('value') || '').trim().toLowerCase();
      if (text.includes('verify it\'s you') || text.includes('verify you are human') || text.includes('xác minh bạn là')) {
        simulateHumanClick(btn);
        return;
      }
    }
  }

  // Khởi chạy quan sát DOM
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
      checkTurnstileAndVerify();
    });
  } else {
    checkTurnstileAndVerify();
  }

  const observer = new MutationObserver(() => {
    checkTurnstileAndVerify();
  });

  try {
    observer.observe(document.documentElement || document.body, {
      childList: true,
      subtree: true,
      attributes: true
    });
  } catch (e) {}

  // Quét định kỳ nhẹ nhàng
  const timer = setInterval(() => {
    if (solved) {
      clearInterval(timer);
      return;
    }
    checkTurnstileAndVerify();
  }, 1000);

  // 4. Hỗ trợ tự động gửi prompt qua phím tắt Ctrl+Enter hoặc sự kiện afm_send_prompt
  window.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      const btn = document.querySelector('[aria-label="Bắt đầu tạo"], [aria-label="Start generation"], button[aria-label*="tạo" i], button[aria-label*="generation" i]');
      if (btn) {
        simulateHumanClick(btn);
      }
    }
  });

  window.addEventListener('afm_send_prompt', () => {
    const btn = document.querySelector('[aria-label="Bắt đầu tạo"], [aria-label="Start generation"], button[aria-label*="tạo" i], button[aria-label*="generation" i]');
    if (btn) {
      simulateHumanClick(btn);
    }
  });
})();
