/**
 * Flow Batch Studio - Extension Content Script (MAIN World)
 * Chạy trực tiếp trong execution context của trang web tại document_start
 * trước khi bất kỳ script nào của Google / Cloudflare / bot detector thực thi.
 */
(function () {
  'use strict';

  // 1. Gỡ bỏ dấu vết navigator.webdriver
  try {
    const navProto = Object.getPrototypeOf(navigator);
    if ('webdriver' in navProto) {
      delete navProto.webdriver;
    }
    Object.defineProperty(Navigator.prototype, 'webdriver', {
      get: () => undefined,
      enumerable: true,
      configurable: true
    });
  } catch (e) {}

  // 2. Chuẩn hóa window.chrome giống trình duyệt thật
  try {
    if (!window.chrome) {
      window.chrome = {};
    }
    if (!window.chrome.runtime) {
      window.chrome.runtime = {
        id: undefined,
        connect: function () {},
        sendMessage: function () {},
        onMessage: { addListener: function () {}, removeListener: function () {} }
      };
    }
    if (!window.chrome.loadTimes) {
      window.chrome.loadTimes = function () {
        const perf = window.performance;
        const timing = perf && perf.timing;
        const navStart = timing ? timing.navigationStart / 1000 : Date.now() / 1000;
        return {
          requestTime: navStart,
          startLoadTime: navStart,
          commitLoadTime: navStart + 0.1,
          finishDocumentLoadTime: navStart + 0.3,
          firstPaintTime: navStart + 0.15,
          finishLoadTime: navStart + 0.4,
          wasFetchedViaSpdy: true,
          wasNpnNegotiated: true,
          npnNegotiatedProtocol: 'h2',
          wasAlternateProtocolAvailable: false,
          connectionInfo: 'h2'
        };
      };
    }
    if (!window.chrome.csi) {
      window.chrome.csi = function () {
        const perf = window.performance;
        return {
          startE: perf && perf.timing ? perf.timing.navigationStart : Date.now(),
          onloadT: perf && perf.timing ? perf.timing.loadEventEnd : Date.now(),
          pageT: perf ? perf.now() : 100,
          tran: 15
        };
      };
    }
    if (!window.chrome.app) {
      window.chrome.app = {
        isInstalled: false,
        InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
        RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
      };
    }
  } catch (e) {}

  // 3. Chuẩn hóa navigator.plugins & mimeTypes nếu bị rỗng
  try {
    if (!navigator.plugins || navigator.plugins.length === 0) {
      const mockPlugins = [
        { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'Microsoft Edge PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
        { name: 'WebKit built-in PDF', filename: 'internal-pdf-viewer', description: 'Portable Document Format' }
      ];
      Object.defineProperty(navigator, 'plugins', {
        get: () => mockPlugins,
        enumerable: true,
        configurable: true
      });
    }
  } catch (e) {}

  // 4. Chuẩn hóa navigator.languages
  try {
    if (!navigator.languages || navigator.languages.length === 0) {
      Object.defineProperty(navigator, 'languages', {
        get: () => ['vi-VN', 'vi', 'en-US', 'en'],
        enumerable: true,
        configurable: true
      });
    }
  } catch (e) {}

  // 5. Chuẩn hóa navigator.permissions.query (đặc biệt là 'notifications')
  try {
    if (navigator.permissions && navigator.permissions.query) {
      const origQuery = navigator.permissions.query;
      navigator.permissions.query = function (params) {
        if (params && params.name === 'notifications') {
          const perm = typeof Notification !== 'undefined' ? Notification.permission : 'default';
          return Promise.resolve({
            state: perm === 'default' ? 'prompt' : perm,
            onchange: null
          });
        }
        return origQuery.apply(this, arguments);
      };
    }
  } catch (e) {}

  // 6. Xóa các biến nhận diện tự động hóa
  try {
    delete window.__playwright;
    delete window.__pw_manualStatus;
    delete window.__puppeteer_evaluation_script__;
    delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
    delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;
    delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;
  } catch (e) {}

  // Đánh dấu extension stealth đã áp dụng thành công
  window.__afm_stealth_ready = true;
})();
