import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 개발 서버에서 api(./gradlew :api:bootRun)로 넘긴다
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
