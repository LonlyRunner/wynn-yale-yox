import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: { rollupOptions: { input: [fileURLToPath(new URL('./index.html', import.meta.url)), fileURLToPath(new URL('./holo-card.html', import.meta.url))] } },
  server: { proxy: { '/api': 'http://localhost:8080' } },
  preview: { proxy: { '/api': 'http://localhost:8080' } },
})
