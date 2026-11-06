// Ported from FlowKit (https://github.com/crisng95/flowkit), MIT License, Copyright (c) 2026 tuannguyenhoangit-droid.
/**
 * Content script — bridge between background.js (ISOLATED world) and
 * injected.js (MAIN world).
 *
 * All MAIN world scripts (hijack_bypass.js, recaptcha_enterprise.js,
 * recaptcha__en.js, injected.js) are now loaded via manifest.json
 * content_scripts with "world": "MAIN" — this bypasses the page's
 * Trusted Types CSP entirely. No more createElement('script') needed.
 *
 * This content script only handles the message relay between the two worlds.
 */

chrome.runtime.onMessage.addListener((msg, _, reply) => {
  if (msg.type !== 'GET_CAPTCHA') return;

  const { requestId, pageAction } = msg;

  const handler = (e) => {
    if (e.detail?.requestId === requestId) {
      window.removeEventListener('CAPTCHA_RESULT', handler);
      clearTimeout(timer);
      reply({ token: e.detail.token, error: e.detail.error });
    }
  };

  const timer = setTimeout(() => {
    window.removeEventListener('CAPTCHA_RESULT', handler);
    reply({ error: 'CONTENT_TIMEOUT' });
  }, 25000);

  window.addEventListener('CAPTCHA_RESULT', handler);

  window.dispatchEvent(new CustomEvent('GET_CAPTCHA', {
    detail: { requestId, pageAction },
  }));

  return true; // keep channel open for async reply
});
