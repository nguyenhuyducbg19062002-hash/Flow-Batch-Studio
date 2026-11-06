import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 127.0.0.1 chứ không phải localhost: backend chỉ nghe IPv4 127.0.0.1, còn Node bản mới
      // có thể phân giải localhost thành ::1 (IPv6) và báo ECONNREFUSED.
      '/api': {
        target: 'http://127.0.0.1:8082',
        changeOrigin: true,
      },
    },
  },
  build: {
    // Tự động đóng gói trực tiếp vào thư mục static của Backend Spring Boot
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  }
})
