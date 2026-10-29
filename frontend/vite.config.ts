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
    outDir: 'dist',
    rollupOptions: {
      output: {
        // vendor code changes rarely: separate chunks stay cached across console releases
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query', 'axios'],
          mui: ['@mui/material', '@emotion/react', '@emotion/styled'],
          grid: ['@mui/x-data-grid']
        }
      }
    }
  }
})
