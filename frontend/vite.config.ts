import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// 项目仅使用 19070-19079：前端 19070，后端 19071。
const apiProxyTarget = process.env.VITE_PROXY_TARGET || "http://localhost:19071";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 19070,
    strictPort: true,
    host: true,
    proxy: {
      "/api": { target: apiProxyTarget, changeOrigin: true },
    },
  },
});
