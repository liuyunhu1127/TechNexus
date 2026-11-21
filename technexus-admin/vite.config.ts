import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: { dedupe: ['vue'] },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
    fs: { allow: ['.', '../frontend-shared'] },
  },
  build: { sourcemap: true }
})
