import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// In development the API is proxied so the browser sees one origin (cookies are SameSite=Strict).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.SDLC_API_URL ?? 'http://localhost:8080', changeOrigin: false },
      '/actuator': { target: process.env.SDLC_API_URL ?? 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
});
