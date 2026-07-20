import { computed, reactive } from "vue";

export type AppRole = "USER" | "REVIEWER" | "COMPLIANCE_ADMIN" | "SYSTEM_ADMIN";

export interface CurrentUser {
  userId: string;
  tenantId: string;
  role: AppRole;
  demoMode: boolean;
  disclaimer: string;
}

interface Credentials {
  username: string;
  password: string;
}

interface AuthState {
  initialized: boolean;
  busy: boolean;
  user: CurrentUser | null;
  credentials: Credentials | null;
  error: string;
}

const STORAGE_KEY = "compliance-demo-auth";
const state = reactive<AuthState>({
  initialized: false,
  busy: false,
  user: null,
  credentials: null,
  error: "",
});

export const demoAccounts: Array<{ username: string; password: string; label: string; tenant: string }> = [
  { username: "user@demo.local", password: "demo-change-me", label: "业务用户", tenant: "tenant-a" },
  { username: "reviewer@demo.local", password: "demo-change-me", label: "审核员", tenant: "tenant-a" },
  { username: "compliance@demo.local", password: "demo-change-me", label: "合规管理员", tenant: "tenant-a" },
  { username: "admin@demo.local", password: "admin-change-me", label: "系统管理员", tenant: "跨租户" },
  { username: "tenant-b@demo.local", password: "demo-change-me", label: "租户 B 用户", tenant: "tenant-b" },
];

function encodeBasic(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary);
}

export function authHeader(): Record<string, string> {
  if (!state.credentials) return {};
  return { Authorization: `Basic ${encodeBasic(`${state.credentials.username}:${state.credentials.password}`)}` };
}

function readStored(): Credentials | null {
  if (typeof sessionStorage === "undefined") return null;
  try {
    const parsed = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || "null") as Credentials | null;
    return parsed?.username && parsed?.password ? parsed : null;
  } catch {
    return null;
  }
}

async function fetchCurrent(credentials: Credentials): Promise<CurrentUser> {
  const header = { Authorization: `Basic ${encodeBasic(`${credentials.username}:${credentials.password}`)}` };
  let response: Response;
  try {
    response = await fetch("/api/auth/me", { headers: header });
  } catch {
    throw new Error("无法连接后端服务，请检查 19071 端口和网络状态");
  }
  const payload = await response.json().catch(() => null) as {
    code?: number;
    message?: string;
    data?: CurrentUser;
  } | null;
  if (!response.ok || !payload || payload.code !== 0 || !payload.data) {
    throw new Error(response.status === 401 ? "账号或密码不正确" : payload?.message || "登录失败");
  }
  return payload.data;
}

export async function login(username: string, password: string): Promise<void> {
  state.busy = true;
  state.error = "";
  const credentials = { username: username.trim(), password };
  try {
    const user = await fetchCurrent(credentials);
    state.credentials = credentials;
    state.user = user;
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(credentials));
  } catch (error) {
    state.credentials = null;
    state.user = null;
    state.error = error instanceof Error ? error.message : "登录失败";
    sessionStorage.removeItem(STORAGE_KEY);
    throw error;
  } finally {
    state.busy = false;
    state.initialized = true;
  }
}

export async function initializeAuth(): Promise<void> {
  if (state.initialized) return;
  const stored = readStored();
  if (!stored) {
    state.initialized = true;
    return;
  }
  try {
    await login(stored.username, stored.password);
  } catch {
    // login() already clears invalid or stale session credentials.
  } finally {
    state.initialized = true;
  }
}

export function logout(): void {
  state.user = null;
  state.credentials = null;
  state.error = "";
  sessionStorage.removeItem(STORAGE_KEY);
}

export function useAuth() {
  return {
    state,
    authenticated: computed(() => Boolean(state.user && state.credentials)),
  };
}

const roleRank: Record<AppRole, number> = {
  USER: 0,
  REVIEWER: 1,
  COMPLIANCE_ADMIN: 2,
  SYSTEM_ADMIN: 3,
};

export function hasRole(required: AppRole): boolean {
  return state.user ? roleRank[state.user.role] >= roleRank[required] : false;
}
