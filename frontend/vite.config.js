import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const target = process.env.BACKEND_URL ?? 'http://localhost:8000';
const proxy = { '/api': { target, changeOrigin: true } };

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, host: true, proxy },
  preview: { port: 5173, host: true, proxy },
  test: {
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
    globals: true,
  },
});
