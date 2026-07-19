<script setup lang="ts">
import { dismissToast, useToastState } from "../toast";

const toasts = useToastState();
</script>

<template>
  <div class="toast-host" aria-live="polite">
    <transition-group name="toast">
      <div
        v-for="t in toasts.items"
        :key="t.id"
        :class="['toast-item', `toast-${t.type}`]"
        role="alert"
      >
        <span class="toast-icon">{{ t.type === "error" ? "✕" : t.type === "success" ? "✓" : "ℹ" }}</span>
        <span class="toast-msg">{{ t.message }}</span>
        <button class="toast-close" aria-label="关闭" @click="dismissToast(t.id)">×</button>
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: 16px;
  right: 16px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: min(420px, calc(100vw - 32px));
  pointer-events: none;
}
.toast-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  border: 1px solid transparent;
  pointer-events: auto;
  font-size: 13px;
  line-height: 1.5;
}
.toast-error { background: #fef2f2; border-color: #fecaca; color: #991b1b; }
.toast-success { background: #f0fdf4; border-color: #bbf7d0; color: #166534; }
.toast-info { background: #eff6ff; border-color: #bfdbfe; color: #1e40af; }
.toast-icon { font-weight: 700; flex-shrink: 0; width: 18px; text-align: center; }
.toast-msg { flex: 1; word-break: break-word; }
.toast-close {
  background: transparent;
  border: none;
  color: inherit;
  opacity: 0.6;
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
  padding: 0 2px;
}
.toast-close:hover { opacity: 1; }
.toast-enter-active,
.toast-leave-active { transition: all 0.25s ease; }
.toast-enter-from { opacity: 0; transform: translateX(24px); }
.toast-leave-to { opacity: 0; transform: translateX(24px); }
</style>
