import { reactive } from "vue";

export type ToastType = "error" | "success" | "info";

export interface ToastItem {
  id: number;
  type: ToastType;
  message: string;
}

const state = reactive<{ items: ToastItem[] }>({ items: [] });
let seq = 0;

function dismiss(id: number) {
  state.items = state.items.filter((t) => t.id !== id);
}

export function showToast(message: string, type: ToastType = "error", durationMs = 4200) {
  const id = ++seq;
  state.items.push({ id, type, message });
  if (durationMs > 0) {
    setTimeout(() => dismiss(id), durationMs);
  }
}

export function useToastState() {
  return state;
}

export { dismiss as dismissToast };
