/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080'

  return {
    base: env.VITE_BASE_PATH || '/',
    plugins: [react()],
    server: {
      // 개발 서버에서 API를 같은 주소로 호출해 쿠키가 함께 전달되게 한다.
      proxy: {
        '/api': apiTarget,
        '/auth': apiTarget,
      },
    },
    test: {
      environment: 'node',
    },
  }
})
