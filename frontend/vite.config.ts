import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:18080',
      '/np': 'http://localhost:18081'
    }
  },
  build: {
    outDir: 'dist'
  }
})
