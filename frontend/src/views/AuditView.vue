<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  AlertTriangle,
  CheckCircle2,
  Copy,
  Fingerprint,
  RefreshCw,
  Search,
  ShieldCheck,
  ShieldX,
} from "@lucide/vue";
import { api, type AuditEvent, type ChainVerification } from "../api";
import { useAuth } from "../auth";
import { showToast } from "../toast";
import { formatDateTime, shortKey } from "../ui";

const { state: authState } = useAuth();
const events = ref<AuditEvent[]>([]);
const verification = ref<ChainVerification | null>(null);
const loading = ref(true);
const verifying = ref(false);
const error = ref("");
const query = ref("");
const tenantId = ref(authState.user?.role === "SYSTEM_ADMIN" ? "tenant-a" : authState.user?.tenantId || "");
let controller: AbortController | null = null;

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  return events.value.filter((event) => !keyword
    || event.action.toLowerCase().includes(keyword)
    || event.resourceType.toLowerCase().includes(keyword)
    || event.resourceId.toLowerCase().includes(keyword)
    || event.actorId.toLowerCase().includes(keyword)
    || event.tenantId.toLowerCase().includes(keyword));
});
const tenants = computed(() => [...new Set(events.value.map((event) => event.tenantId))]);

async function load() {
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    events.value = await api.listAuditEvents(500, controller.signal);
    if (authState.user?.role === "SYSTEM_ADMIN" && !tenantId.value && tenants.value.length) {
      tenantId.value = tenants.value[0];
    }
    await verify();
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === "AbortError") return;
    error.value = reason instanceof Error ? reason.message : "审计事件加载失败";
  } finally {
    loading.value = false;
  }
}

async function verify() {
  if (authState.user?.role === "SYSTEM_ADMIN" && !tenantId.value.trim()) {
    verification.value = null;
    return;
  }
  verifying.value = true;
  try {
    verification.value = await api.verifyAudit(
      authState.user?.role === "SYSTEM_ADMIN" ? tenantId.value.trim() : undefined,
      controller?.signal,
    );
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "哈希链校验失败", "error");
  } finally {
    verifying.value = false;
  }
}

async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    showToast("摘要已复制", "success");
  } catch {
    showToast("浏览器未授权剪贴板", "error");
  }
}

function details(value: string) {
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
}

onMounted(load);
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <div class="workspace-page">
    <header class="page-heading">
      <div><span class="eyebrow">Tamper-evident trail</span><h1>审计事件</h1><p>租户内 SHA-256 前向哈希链，可检测数据库事件被改写；不等同于第三方存证。</p></div>
      <button class="button secondary" type="button" :disabled="loading" @click="load"><RefreshCw :size="16" :class="{ rotating: loading }" /> 刷新</button>
    </header>

    <section class="verification-panel">
      <div :class="['verification-mark', verification?.valid ? 'valid' : verification ? 'invalid' : 'idle']">
        <CheckCircle2 v-if="verification?.valid" :size="28" />
        <ShieldX v-else-if="verification" :size="28" />
        <ShieldCheck v-else :size="28" />
      </div>
      <div class="verification-copy">
        <span class="eyebrow">Chain verification</span>
        <strong v-if="verification?.valid">哈希链校验通过</strong>
        <strong v-else-if="verification">检测到链条不一致</strong>
        <strong v-else>等待校验</strong>
        <small v-if="verification">校验 {{ verification.eventCount }} 个事件<span v-if="verification.brokenAt"> · 断点 {{ verification.brokenAt }}</span></small>
      </div>
      <label v-if="authState.user?.role === 'SYSTEM_ADMIN'" class="field compact">
        <span>校验租户</span>
        <input v-model="tenantId" list="tenant-options" placeholder="tenant-a" @change="verify" />
        <datalist id="tenant-options"><option v-for="tenant in tenants" :key="tenant" :value="tenant"></option></datalist>
      </label>
      <div v-if="verification" class="latest-hash">
        <span>最新摘要</span><code>{{ shortKey(verification.latestHash, 22) }}</code>
        <button class="icon-button inverse" type="button" title="复制完整摘要" @click="copy(verification.latestHash)"><Copy :size="15" /></button>
      </div>
      <button class="button light" type="button" :disabled="verifying" @click="verify"><span v-if="verifying" class="spinner small-spinner"></span><Fingerprint v-else :size="16" /> 重新校验</button>
    </section>

    <section class="register-section audit-register">
      <div class="register-toolbar">
        <div class="search-control"><Search :size="16" /><input v-model="query" type="search" placeholder="搜索动作、资源、操作者或租户" aria-label="搜索审计事件" /></div>
        <span class="result-count">{{ filtered.length }} / {{ events.length }} 条</span>
      </div>
      <div v-if="loading" class="table-state"><span class="spinner"></span>读取审计链</div>
      <div v-else-if="error" class="error-state" role="alert"><AlertTriangle :size="23" /><strong>审计数据加载失败</strong><span>{{ error }}</span><button class="button secondary" type="button" @click="load">重试</button></div>
      <div v-else-if="!filtered.length" class="empty-state"><ShieldCheck :size="34" /><strong>{{ events.length ? "没有符合搜索条件的事件" : "审计链尚无事件" }}</strong><span>业务写操作发生后会在对应租户链上追加记录。</span></div>
      <div v-else class="audit-timeline">
        <article v-for="event in filtered" :key="event.eventKey">
          <div class="timeline-node"><span></span></div>
          <div class="event-sheet">
            <header>
              <div><strong>{{ event.action }}</strong><span>{{ event.resourceType }} / {{ event.resourceId }}</span></div>
              <time>{{ formatDateTime(event.createdAt) }}</time>
            </header>
            <dl>
              <div><dt>操作者</dt><dd>{{ event.actorId }} · {{ event.actorRole }}</dd></div>
              <div><dt>租户</dt><dd>{{ event.tenantId }}</dd></div>
              <div><dt>状态变化</dt><dd>{{ event.fromState || "∅" }} → {{ event.toState || "∅" }}</dd></div>
              <div><dt>事件编号</dt><dd class="mono">{{ event.eventKey }}</dd></div>
            </dl>
            <details>
              <summary>查看事件详情与哈希</summary>
              <pre>{{ details(event.detailsJson) }}</pre>
              <div class="hash-row"><span>前序</span><code>{{ event.previousHash }}</code><button class="icon-button" type="button" title="复制前序哈希" @click="copy(event.previousHash)"><Copy :size="14" /></button></div>
              <div class="hash-row"><span>本条</span><code>{{ event.eventHash }}</code><button class="icon-button" type="button" title="复制事件哈希" @click="copy(event.eventHash)"><Copy :size="14" /></button></div>
            </details>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.verification-panel { min-height: 104px; display: flex; align-items: center; gap: 16px; margin-bottom: 22px; padding: 18px; color: white; background: var(--ink-900); border: 1px solid #353a37; }.verification-mark { width: 52px; height: 52px; display: grid; place-items: center; flex: 0 0 auto; border: 1px solid #58605b; }.verification-mark.valid { color: #a4dfba; border-color: #4b8d64; background: rgba(52, 121, 78, .18); }.verification-mark.invalid { color: #ffb0ac; border-color: #a54843; background: rgba(166, 54, 48, .18); }
.verification-copy { display: grid; min-width: 210px; gap: 3px; }.verification-copy .eyebrow { color: #909a94; }.verification-copy strong { font-family: var(--font-display); font-size: 17px; }.verification-copy small { color: #aeb5b1; font-size: 9px; }
.verification-panel .field { margin-left: auto; color: white; }.verification-panel .field span { color: #b8bfbb; }.verification-panel input { min-width: 150px; color: white; background: #292e2b; border-color: #505753; }.latest-hash { display: grid; grid-template-columns: auto auto auto; align-items: center; gap: 7px; padding: 8px 10px; border: 1px solid #4a514d; }.latest-hash > span { grid-column: 1 / -1; color: #8f9893; font-size: 8px; text-transform: uppercase; }.latest-hash code { font-size: 9px; }
.audit-register { background: transparent; border: 0; }.audit-register .register-toolbar { background: var(--paper); border: 1px solid var(--line-strong); }
.audit-timeline { position: relative; display: grid; gap: 0; padding: 18px 0; }.audit-timeline::before { content: ""; position: absolute; top: 18px; bottom: 18px; left: 18px; width: 1px; background: var(--line-strong); }.audit-timeline article { position: relative; display: grid; grid-template-columns: 37px 1fr; gap: 10px; }.timeline-node { display: grid; justify-content: center; padding-top: 21px; }.timeline-node span { z-index: 1; width: 9px; height: 9px; background: var(--green-600); border: 2px solid var(--canvas); border-radius: 50%; box-shadow: 0 0 0 1px var(--green-600); }
.event-sheet { margin-bottom: 12px; padding: 15px 17px; background: var(--paper); border: 1px solid var(--line-strong); }.event-sheet > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }.event-sheet header div { display: grid; gap: 3px; }.event-sheet header strong { font-family: var(--font-mono); font-size: 11px; }.event-sheet header span, .event-sheet time { color: var(--ink-500); font-size: 9px; }
.event-sheet dl { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 14px 0 0; }.event-sheet dl div { min-width: 0; }.event-sheet dt { color: var(--ink-500); font-size: 8px; text-transform: uppercase; }.event-sheet dd { margin: 3px 0 0; overflow-wrap: anywhere; font-size: 10px; }
.event-sheet details { margin-top: 13px; padding-top: 11px; border-top: 1px solid var(--line); }.event-sheet summary { cursor: pointer; color: var(--green-800); font-size: 10px; font-weight: 700; }.event-sheet pre { max-height: 180px; padding: 10px; overflow: auto; color: #d6ddd8; background: var(--ink-900); font-size: 9px; }.hash-row { display: grid; grid-template-columns: 42px minmax(0, 1fr) auto; align-items: center; gap: 8px; margin-top: 7px; }.hash-row span { color: var(--ink-500); font-size: 8px; }.hash-row code { overflow: hidden; text-overflow: ellipsis; font-size: 8px; }
@media (max-width: 900px) { .verification-panel { flex-wrap: wrap; }.verification-panel .field { margin-left: 0; }.event-sheet dl { grid-template-columns: 1fr 1fr; } }
@media (max-width: 560px) { .latest-hash { width: 100%; }.verification-panel .button { width: 100%; }.event-sheet > header { flex-direction: column; }.event-sheet dl { grid-template-columns: 1fr; } }
</style>
