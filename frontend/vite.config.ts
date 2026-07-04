import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// 开发环境把 /api 代理到后端 8080
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8090", changeOrigin: true },
    },
  },
});
