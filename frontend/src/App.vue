<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { RouterLink, RouterView, useRoute } from "vue-router";
import {
  BookOpenCheck,
  ChevronDown,
  FileStack,
  ListChecks,
  LogOut,
  ScanSearch,
  ShieldCheck,
  WifiOff,
} from "@lucide/vue";
import LoginGate from "./components/LoginGate.vue";
import { hasRole, initializeAuth, logout, useAuth } from "./auth";
import { showToast } from "./toast";

const { state, authenticated } = useAuth();
const route = useRoute();
const online = ref(navigator.onLine);
const accountOpen = ref(false);

const roleLabel: Record<string, string> = {
  USER: "业务用户",
  REVIEWER: "审核员",
  COMPLIANCE_ADMIN: "合规管理员",
  SYSTEM_ADMIN: "系统管理员",
};

const nav = computed(() => [
  { to: "/documents", label: "文档", icon: FileStack, visible: true },
  { to: "/reviews", label: "审核", icon: ScanSearch, visible: true },
  { to: "/remediations", label: "整改", icon: ListChecks, visible: true },
  { to: "/regulations", label: "法规库", icon: BookOpenCheck, visible: true },
  { to: "/audit", label: "审计", icon: ShieldCheck, visible: hasRole("COMPLIANCE_ADMIN") },
].filter((item) => item.visible));

function setOnline() { online.value = true; }
function setOffline() { online.value = false; }
function expireSession() {
  if (authenticated.value) {
    logout();
    showToast("会话已失效，请重新登录", "error");
  }
}
function signOut() {
  accountOpen.value = false;
  logout();
}

onMounted(async () => {
  window.addEventListener("online", setOnline);
  window.addEventListener("offline", setOffline);
  window.addEventListener("compliance:auth-expired", expireSession);
  await initializeAuth();
});

onBeforeUnmount(() => {
  window.removeEventListener("online", setOnline);
  window.removeEventListener("offline", setOffline);
  window.removeEventListener("compliance:auth-expired", expireSession);
});
</script>

<template>
  <div v-if="!state.initialized" class="boot-screen" aria-busy="true">
    <span class="spinner"></span>
    <span>正在验证本地演示会话</span>
  </div>

  <LoginGate v-else-if="!authenticated" />

  <div v-else class="app-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <div v-if="!online" class="offline-strip" role="status">
      <WifiOff :size="15" aria-hidden="true" />
      网络已断开；已加载内容可继续查看，写操作将在恢复连接后可用
    </div>

    <header class="app-header">
      <RouterLink to="/documents" class="brand" aria-label="Compliance Desk 首页">
        <span class="brand-mark">CD</span>
        <span class="brand-copy">
          <strong>Compliance Desk</strong>
          <small>证据可追溯审核台</small>
        </span>
      </RouterLink>

      <nav class="primary-nav" aria-label="主导航">
        <RouterLink v-for="item in nav" :key="item.to" :to="item.to" class="nav-link">
          <component :is="item.icon" :size="17" stroke-width="1.8" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="header-meta">
        <span class="mode-badge" title="离线 Mock 仅整理确定性工具结果">Mock 文本模式</span>
        <div class="account-wrap">
          <button
            class="account-trigger"
            type="button"
            :aria-expanded="accountOpen"
            aria-haspopup="menu"
            title="当前身份与退出"
            @click="accountOpen = !accountOpen"
          >
            <span class="account-avatar">{{ state.user?.role.charAt(0) }}</span>
            <span class="account-copy">
              <strong>{{ roleLabel[state.user?.role || ""] }}</strong>
              <small>{{ state.user?.tenantId }}</small>
            </span>
            <ChevronDown :size="15" aria-hidden="true" />
          </button>
          <div v-if="accountOpen" class="account-menu" role="menu">
            <div class="account-detail">
              <strong>{{ state.user?.userId }}</strong>
              <span>{{ state.user?.tenantId }} · {{ state.user?.role }}</span>
            </div>
            <button type="button" role="menuitem" @click="signOut">
              <LogOut :size="16" aria-hidden="true" />
              退出并切换角色
            </button>
          </div>
        </div>
      </div>
    </header>

    <main id="main-content" class="app-main" tabindex="-1">
      <RouterView :key="String(route.params.reviewKey || route.path)" />
    </main>

    <nav class="mobile-nav" aria-label="移动端主导航">
      <RouterLink v-for="item in nav" :key="item.to" :to="item.to">
        <component :is="item.icon" :size="18" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <ToastHost />
  </div>
</template>

<style scoped>
.boot-screen {
  min-height: 100%;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 12px;
  color: var(--ink-500);
  background: var(--canvas);
}
.app-shell { min-height: 100%; background: var(--canvas); }
.offline-strip {
  min-height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 6px 16px;
  color: #612b11;
  background: var(--amber-100);
  border-bottom: 1px solid var(--amber-300);
  font-size: 12px;
  font-weight: 650;
}
.app-header {
  position: sticky;
  top: 0;
  z-index: 40;
  min-height: 68px;
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto minmax(220px, 1fr);
  align-items: center;
  gap: 24px;
  padding: 0 28px;
  background: rgba(250, 250, 248, 0.96);
  border-bottom: 1px solid var(--line-strong);
  backdrop-filter: blur(14px);
}
.brand { display: inline-flex; align-items: center; gap: 11px; color: inherit; text-decoration: none; width: max-content; }
.brand-mark {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  color: white;
  background: var(--ink-900);
  border-radius: 5px;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0;
}
.brand-copy { display: grid; line-height: 1.15; }
.brand-copy strong { font-family: var(--font-display); font-size: 16px; letter-spacing: 0; }
.brand-copy small { margin-top: 4px; color: var(--ink-500); font-size: 11px; }
.primary-nav { display: flex; align-items: stretch; height: 68px; }
.nav-link {
  position: relative;
  min-width: 78px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 14px;
  color: var(--ink-500);
  text-decoration: none;
  font-size: 13px;
  font-weight: 650;
}
.nav-link::after { content: ""; position: absolute; left: 14px; right: 14px; bottom: -1px; height: 3px; background: transparent; }
.nav-link:hover { color: var(--ink-900); background: var(--paper); }
.nav-link.router-link-active { color: var(--ink-900); }
.nav-link.router-link-active::after { background: var(--red-600); }
.header-meta { display: flex; justify-content: flex-end; align-items: center; gap: 12px; }
.mode-badge {
  padding: 4px 7px;
  border: 1px solid var(--amber-300);
  border-radius: 4px;
  color: #6b3419;
  background: var(--amber-100);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0;
}
.account-wrap { position: relative; }
.account-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 6px;
  color: var(--ink-900);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
}
.account-trigger:hover, .account-trigger[aria-expanded="true"] { border-color: var(--line); background: var(--paper); }
.account-avatar { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; color: white; background: var(--green-700); font-size: 11px; font-weight: 800; }
.account-copy { display: grid; min-width: 82px; text-align: left; line-height: 1.15; }
.account-copy strong { font-size: 12px; }
.account-copy small { margin-top: 3px; color: var(--ink-500); font-size: 10px; font-family: var(--font-mono); }
.account-menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 260px;
  padding: 8px;
  background: var(--paper);
  border: 1px solid var(--line-strong);
  border-radius: 7px;
  box-shadow: var(--shadow-lg);
}
.account-detail { display: grid; gap: 3px; padding: 9px 10px 12px; border-bottom: 1px solid var(--line); }
.account-detail strong { font-size: 12px; overflow-wrap: anywhere; }
.account-detail span { color: var(--ink-500); font-size: 10px; font-family: var(--font-mono); }
.account-menu button { width: 100%; display: flex; align-items: center; gap: 9px; margin-top: 6px; padding: 9px 10px; border: 0; border-radius: 4px; color: var(--red-700); background: transparent; font-weight: 650; text-align: left; }
.account-menu button:hover { background: var(--red-50); }
.app-main { min-height: calc(100vh - 69px); padding: 28px; }
.mobile-nav { display: none; }

@media (max-width: 980px) {
  .app-header { grid-template-columns: 1fr auto; padding: 0 18px; }
  .primary-nav { display: none; }
  .header-meta { grid-column: 2; }
  .app-main { padding: 22px 18px 86px; }
  .mobile-nav {
    position: fixed;
    z-index: 45;
    left: 10px;
    right: 10px;
    bottom: 10px;
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: 1fr;
    min-height: 58px;
    padding: 5px;
    background: rgba(26, 29, 27, .96);
    border: 1px solid #373b38;
    border-radius: 8px;
    box-shadow: var(--shadow-lg);
  }
  .mobile-nav a { display: grid; place-content: center; justify-items: center; gap: 3px; color: #b7bdb9; text-decoration: none; font-size: 10px; font-weight: 650; border-radius: 4px; }
  .mobile-nav a.router-link-active { color: white; background: #343936; }
}
@media (max-width: 560px) {
  .app-header { min-height: 60px; padding: 0 12px; gap: 8px; }
  .brand-mark { width: 32px; height: 32px; }
  .brand-copy small, .mode-badge, .account-copy { display: none; }
  .app-main { padding: 18px 12px 82px; }
}
</style>
