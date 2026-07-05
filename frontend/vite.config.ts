import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// 本地默认 8090；Docker Compose 内通过 VITE_PROXY_TARGET=http://backend:8080 覆盖
const apiProxyTarget = process.env.VITE_PROXY_TARGET || "http://localhost:8090";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    host: true,
    proxy: {
      "/api": { target: apiProxyTarget, changeOrigin: true },
    },
  },
});
