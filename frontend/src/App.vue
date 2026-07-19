<script setup lang="ts">
import { RouterLink, RouterView } from "vue-router";

const nav = [
  { to: "/upload", label: "文档上传", icon: "📄" },
  { to: "/report", label: "审核报告", icon: "📋" },
];
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <div class="logo">合</div>
        <div>
          <div class="brand-name">合规文档审核 Agent</div>
          <div class="brand-sub">上传合规材料 · AI 流式审核 · 风险项溯源</div>
        </div>
      </div>
      <nav class="nav">
        <RouterLink
          v-for="n in nav"
          :key="n.to"
          :to="n.to"
          class="nav-item"
          active-class="active"
        >
          <span>{{ n.icon }}</span>{{ n.label }}
        </RouterLink>
      </nav>
    </header>
    <main class="content">
      <RouterView />
    </main>
    <ToastHost />
  </div>
</template>

<style scoped>
.app { display: flex; flex-direction: column; height: 100%; }
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.brand { display: flex; align-items: center; gap: 12px; }
.logo {
  width: 40px; height: 40px; border-radius: 12px;
  background: linear-gradient(135deg, #0f766e, #115e59);
  color: #fff; font-weight: 700; font-size: 18px;
  display: grid; place-items: center;
}
.brand-name { font-weight: 700; font-size: 16px; }
.brand-sub { font-size: 12px; color: var(--muted); }
.nav { display: flex; gap: 6px; }
.nav-item {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 16px; border-radius: 10px;
  text-decoration: none; color: var(--muted); font-weight: 600;
}
.nav-item:hover { background: var(--primary-soft); color: var(--primary); }
.nav-item.active { background: var(--primary); color: #fff; }
.content { flex: 1; overflow: auto; padding: 24px; }
</style>
