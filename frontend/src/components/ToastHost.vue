<script setup lang="ts">
import { AlertTriangle, CheckCircle2, Info, X } from "@lucide/vue";
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
        <span class="toast-icon">
          <AlertTriangle v-if="t.type === 'error'" :size="17" />
          <CheckCircle2 v-else-if="t.type === 'success'" :size="17" />
          <Info v-else :size="17" />
        </span>
        <span class="toast-msg">{{ t.message }}</span>
        <button class="toast-close" aria-label="关闭" @click="dismissToast(t.id)"><X :size="15" /></button>
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
  border-radius: 4px;
  box-shadow: var(--shadow-lg);
  border: 1px solid transparent;
  pointer-events: auto;
  font-size: 13px;
  line-height: 1.5;
}
.toast-error { background: var(--red-50); border-color: var(--red-300); color: var(--red-700); }
.toast-success { background: var(--green-50); border-color: var(--green-200); color: var(--green-800); }
.toast-info { background: var(--paper); border-color: var(--line-strong); color: var(--ink-700); }
.toast-icon { font-weight: 700; flex-shrink: 0; width: 18px; text-align: center; }
.toast-msg { flex: 1; word-break: break-word; }
.toast-close {
  background: transparent;
  border: none;
  color: inherit;
  opacity: 0.6;
  cursor: pointer;
  line-height: 1;
  padding: 1px;
}
.toast-close:hover { opacity: 1; }
.toast-enter-active,
.toast-leave-active { transition: all 0.25s ease; }
.toast-enter-from { opacity: 0; transform: translateX(24px); }
.toast-leave-to { opacity: 0; transform: translateX(24px); }
</style>
