<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { ArrowRight, BadgeCheck, FileSearch2, KeyRound, ShieldCheck, UserRound } from "@lucide/vue";
import { demoAccounts, login, useAuth } from "../auth";

const router = useRouter();
const { state } = useAuth();
const username = ref(demoAccounts[0].username);
const password = ref(demoAccounts[0].password);
const selected = computed(() => demoAccounts.find((account) => account.username === username.value));

function choose(account: (typeof demoAccounts)[number]) {
  username.value = account.username;
  password.value = account.password;
}

async function submit() {
  try {
    await login(username.value, password.value);
    await router.replace("/documents");
  } catch {
    // Auth state exposes the normalized error next to the fields.
  }
}
</script>

<template>
  <main class="login-shell">
    <section class="login-context" aria-labelledby="login-title">
      <div class="context-kicker"><ShieldCheck :size="16" /> Compliance Doc Agent v1 RC</div>
      <h1 id="login-title">把每一条风险<br />落回证据。</h1>
      <p>规则、法规引用、原文位置、人工意见与整改状态使用同一审核快照。</p>

      <div class="evidence-index" aria-label="工程能力摘要">
        <div><FileSearch2 :size="18" /><span><b>8</b> 个可验证工具</span></div>
        <div><BadgeCheck :size="18" /><span><b>4</b> 级角色权限</span></div>
        <div><KeyRound :size="18" /><span><b>0</b> 外部密钥启动</span></div>
      </div>

      <p class="legal-note">本地脱敏演示环境。结果仅供人工复核，不替代律师意见，也不构成法定认证。</p>
    </section>

    <section class="login-panel" aria-label="演示账户登录">
      <div class="panel-heading">
        <span class="step-label">01 / 身份</span>
        <h2>进入审核台</h2>
        <p>选择演示角色，服务端会真实校验角色与租户。</p>
      </div>

      <div class="account-grid" aria-label="常用演示角色">
        <button
          v-for="account in demoAccounts.slice(0, 4)"
          :key="account.username"
          type="button"
          :class="{ selected: username === account.username }"
          @click="choose(account)"
        >
          <UserRound :size="16" />
          <span><strong>{{ account.label }}</strong><small>{{ account.tenant }}</small></span>
        </button>
      </div>

      <form @submit.prevent="submit">
        <label>
          <span>账户</span>
          <input v-model.trim="username" autocomplete="username" required aria-describedby="account-hint" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" required />
        </label>
        <p id="account-hint" class="account-hint">{{ selected?.label || "自定义账户" }} · {{ selected?.tenant || "由服务端判定租户" }}</p>
        <p v-if="state.error" class="form-error" role="alert">{{ state.error }}</p>
        <button class="login-submit" type="submit" :disabled="state.busy">
          <span v-if="state.busy" class="spinner spinner-inverse"></span>
          <template v-else>登录审核台 <ArrowRight :size="17" /></template>
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-shell {
  min-height: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(420px, .85fr);
  background: var(--canvas);
}
.login-context {
  position: relative;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(40px, 8vw, 120px);
  overflow: hidden;
  color: #f5f6f3;
  background: #1c211e;
}
.context-kicker { position: relative; display: inline-flex; align-items: center; gap: 8px; color: #d7ded9; font-family: var(--font-mono); font-size: 11px; font-weight: 700; letter-spacing: 0; text-transform: uppercase; }
.login-context h1 { position: relative; max-width: 720px; margin: 34px 0 22px; font-family: var(--font-display); font-size: 72px; font-weight: 720; line-height: .98; letter-spacing: 0; }
.login-context > p { position: relative; max-width: 590px; margin: 0; color: #b8c1bb; font-size: 17px; line-height: 1.75; }
.evidence-index { position: relative; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); max-width: 650px; margin-top: 58px; border-top: 1px solid #49514c; border-bottom: 1px solid #49514c; }
.evidence-index div { display: flex; align-items: center; gap: 10px; padding: 18px 14px 18px 0; color: #dfe4e0; }
.evidence-index div + div { padding-left: 18px; border-left: 1px solid #49514c; }
.evidence-index span { display: grid; color: #aeb8b1; font-size: 11px; }
.evidence-index b { color: white; font-family: var(--font-display); font-size: 22px; line-height: 1; }
.login-context .legal-note { position: relative; margin-top: 28px; color: #89958d; font-size: 11px; line-height: 1.6; }
.login-panel { align-self: center; width: min(100%, 590px); padding: 52px clamp(30px, 6vw, 82px); }
.panel-heading { margin-bottom: 30px; }
.step-label { color: var(--red-700); font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0; text-transform: uppercase; }
.panel-heading h2 { margin: 9px 0 7px; font-family: var(--font-display); font-size: 32px; letter-spacing: 0; }
.panel-heading p { margin: 0; color: var(--ink-600); font-size: 13px; }
.account-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 24px; }
.account-grid button { display: flex; align-items: center; gap: 9px; min-height: 54px; padding: 9px 10px; text-align: left; color: var(--ink-700); background: var(--paper); border: 1px solid var(--line); border-radius: 6px; }
.account-grid button:hover { border-color: var(--ink-400); }
.account-grid button.selected { color: var(--ink-900); border-color: var(--ink-900); box-shadow: inset 3px 0 var(--red-600); }
.account-grid span { display: grid; line-height: 1.2; }
.account-grid strong { font-size: 12px; }
.account-grid small { margin-top: 4px; color: var(--ink-600); font-size: 10px; font-family: var(--font-mono); }
form { display: grid; gap: 15px; }
label { display: grid; gap: 7px; }
label > span { color: var(--ink-700); font-size: 12px; font-weight: 700; }
input { width: 100%; height: 44px; padding: 0 12px; color: var(--ink-900); background: var(--paper); border: 1px solid var(--line-strong); border-radius: 5px; outline: none; }
input:focus { border-color: var(--green-700); box-shadow: 0 0 0 3px var(--green-100); }
.account-hint { margin: -6px 0 0; color: var(--ink-600); font-size: 10px; }
.form-error { margin: -3px 0 0; padding: 9px 10px; color: var(--red-700); background: var(--red-50); border-left: 3px solid var(--red-600); font-size: 12px; }
.login-submit { min-height: 46px; display: flex; align-items: center; justify-content: center; gap: 9px; margin-top: 3px; color: white; background: var(--ink-900); border: 1px solid var(--ink-900); border-radius: 5px; font-weight: 750; }
.login-submit:hover:not(:disabled) { background: var(--green-800); border-color: var(--green-800); }
.login-submit:disabled { opacity: .55; cursor: wait; }

@media (max-width: 880px) {
  .login-shell { grid-template-columns: 1fr; }
  .login-context { min-height: auto; padding: 44px 28px; }
  .login-context h1 { margin-top: 26px; font-size: 54px; }
  .evidence-index { margin-top: 36px; }
  .login-panel { padding: 40px 28px 54px; }
}
@media (max-width: 480px) {
  .login-context { padding: 34px 20px; }
  .login-context h1 { font-size: 42px; }
  .login-context > p { font-size: 13px; }
  .evidence-index { grid-template-columns: 1fr; }
  .evidence-index div + div { padding-left: 0; border-left: 0; border-top: 1px solid #49514c; }
  .account-grid { grid-template-columns: 1fr; }
  .login-panel { padding: 32px 20px 46px; }
}
</style>
