<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { AlertTriangle, BookOpenCheck, Database, RefreshCw, Search, ShieldQuestion } from "@lucide/vue";
import { api, type RegulationEntry, type RegulationMatch } from "../api";
import { formatDateTime } from "../ui";

const entries = ref<RegulationEntry[]>([]);
const matches = ref<RegulationMatch[] | null>(null);
const loading = ref(true);
const searching = ref(false);
const error = ref("");
const query = ref("");
const scope = ref("");
let controller: AbortController | null = null;

const scopes = computed(() => [...new Set(entries.value.map((entry) => entry.scope))].sort());
const activeEntries = computed(() => entries.value.filter((entry) => !entry.expiryDate || entry.expiryDate >= new Date().toISOString().slice(0, 10)).length);

async function load() {
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    entries.value = await api.listRegulations(controller.signal);
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === "AbortError") return;
    error.value = reason instanceof Error ? reason.message : "法规目录加载失败";
  } finally {
    loading.value = false;
  }
}

async function search() {
  const keyword = query.value.trim();
  if (!keyword) {
    matches.value = null;
    return;
  }
  searching.value = true;
  error.value = "";
  try {
    matches.value = await api.searchRegulations(keyword, scope.value);
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "法规检索失败";
  } finally {
    searching.value = false;
  }
}

function reset() {
  query.value = "";
  scope.value = "";
  matches.value = null;
}

onMounted(load);
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <div class="workspace-page">
    <header class="page-heading">
      <div><span class="eyebrow">Versioned knowledge base</span><h1>演示法规库</h1><p>可检索、带版本与生效信息的仓库内演示依据；零命中会明确返回空结果。</p></div>
      <button class="button secondary" type="button" :disabled="loading" @click="load"><RefreshCw :size="16" :class="{ rotating: loading }" /> 刷新</button>
    </header>

    <div class="demo-disclaimer"><ShieldQuestion :size="20" /><div><strong>非权威法规数据</strong><span>本页全部条目均为脱敏工程演示集，不是法规原文，不构成法律意见；生产环境需接入经授权并持续更新的权威数据源。</span></div></div>

    <section class="metric-strip">
      <div><span>版本化条目</span><strong>{{ entries.length }}</strong></div>
      <div><span>当前有效</span><strong>{{ activeEntries }}</strong></div>
      <div><span>覆盖范围</span><strong>{{ scopes.length }}</strong></div>
      <div><span>数据属性</span><strong class="text-metric">DEMO</strong></div>
    </section>

    <form class="knowledge-search" @submit.prevent="search">
      <div class="search-control"><Search :size="17" /><input v-model="query" type="search" placeholder="例如：无限责任、个人信息最小必要、采购审批" aria-label="法规关键词" /></div>
      <select v-model="scope" class="filter-select" aria-label="法规适用范围"><option value="">全部范围</option><option v-for="item in scopes" :key="item" :value="item">{{ item }}</option></select>
      <button class="button primary" type="submit" :disabled="searching || !query.trim()"><span v-if="searching" class="spinner small-spinner"></span><Search v-else :size="15" /> 检索</button>
      <button v-if="matches !== null" class="button secondary" type="button" @click="reset">返回目录</button>
    </form>

    <div v-if="loading" class="page-loading"><span class="spinner"></span>读取版本化法规目录</div>
    <div v-else-if="error" class="error-state" role="alert"><AlertTriangle :size="23" /><strong>法规数据读取失败</strong><span>{{ error }}</span><button class="button secondary" type="button" @click="load">重试</button></div>
    <div v-else-if="matches !== null && !matches.length" class="empty-state knowledge-empty"><Search :size="32" /><strong>没有匹配依据</strong><span>“{{ query }}”在当前范围内返回 0 条；系统不会用不相关条目填充结果。</span><button class="button secondary" type="button" @click="reset">查看全部条目</button></div>
    <section v-else class="knowledge-grid">
      <article v-for="entry in (matches || entries.map((item) => ({ entry: item, relevanceScore: 0, excerpt: '' })))" :key="entry.entry.code" class="knowledge-card">
        <header>
          <span class="demo-tag">DEMO</span>
          <span class="scope-tag">{{ entry.entry.scope }}</span>
          <span v-if="matches" class="score-tag">相关度 {{ Math.round(entry.relevanceScore * 100) }}%</span>
        </header>
        <div class="knowledge-code"><Database :size="14" /><span>{{ entry.entry.code }}</span></div>
        <h2>{{ entry.entry.title }}</h2>
        <p v-if="matches && entry.excerpt" class="match-excerpt">{{ entry.excerpt }}</p>
        <p v-else>{{ entry.entry.content }}</p>
        <dl>
          <div><dt>版本</dt><dd>{{ entry.entry.versionLabel }}</dd></div>
          <div><dt>条款</dt><dd>{{ entry.entry.articleNo }}</dd></div>
          <div><dt>生效</dt><dd>{{ entry.entry.effectiveDate }}</dd></div>
          <div><dt>失效</dt><dd>{{ entry.entry.expiryDate || "未设置" }}</dd></div>
        </dl>
        <footer><BookOpenCheck :size="15" /><span><strong>{{ entry.entry.sourceName }}</strong><small>目录读取时间 {{ formatDateTime(new Date().toISOString()) }}</small></span></footer>
      </article>
    </section>
  </div>
</template>

<style scoped>
.demo-disclaimer { display: flex; align-items: flex-start; gap: 11px; margin-bottom: 18px; padding: 13px 15px; color: #61320f; background: var(--amber-100); border: 1px solid var(--amber-300); }.demo-disclaimer div { display: grid; gap: 3px; }.demo-disclaimer strong { font-size: 12px; }.demo-disclaimer span { font-size: 10px; line-height: 1.6; }
.text-metric { font-size: 15px !important; letter-spacing: 0; }.knowledge-search { display: grid; grid-template-columns: minmax(260px, 1fr) 180px auto auto; gap: 9px; margin: 22px 0 16px; padding: 13px; background: var(--paper); border: 1px solid var(--line-strong); }.knowledge-search .search-control { width: 100%; }
.knowledge-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr)); gap: 14px; }.knowledge-card { min-height: 340px; display: flex; flex-direction: column; padding: 18px; background: var(--paper); border: 1px solid var(--line-strong); }.knowledge-card > header { display: flex; gap: 6px; }.demo-tag, .scope-tag, .score-tag { padding: 3px 6px; border-radius: 3px; font-size: 9px; font-weight: 800; letter-spacing: 0; }.demo-tag { color: #61320f; background: var(--amber-100); border: 1px solid var(--amber-300); }.scope-tag { color: var(--green-800); background: var(--green-50); border: 1px solid var(--green-200); }.score-tag { margin-left: auto; color: var(--ink-600); background: var(--canvas); border: 1px solid var(--line); }
.knowledge-code { display: flex; align-items: center; gap: 6px; margin-top: 22px; color: var(--ink-500); font-family: var(--font-mono); font-size: 10px; }.knowledge-card h2 { margin: 7px 0 12px; font-family: var(--font-display); font-size: 19px; line-height: 1.35; }.knowledge-card > p { flex: 1; margin: 0; color: var(--ink-700); line-height: 1.75; }.match-excerpt { padding: 10px 12px; background: var(--green-50); border-left: 3px solid var(--green-600); }
.knowledge-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; margin: 18px 0; }.knowledge-card dl div { display: grid; gap: 2px; }.knowledge-card dt { color: var(--ink-500); font-size: 9px; text-transform: uppercase; }.knowledge-card dd { margin: 0; font-size: 11px; }.knowledge-card footer { display: flex; align-items: flex-start; gap: 8px; padding-top: 12px; border-top: 1px solid var(--line); }.knowledge-card footer svg { color: var(--green-700); }.knowledge-card footer span { display: grid; gap: 2px; }.knowledge-card footer strong { font-size: 10px; }.knowledge-card footer small { color: var(--ink-500); font-size: 8px; }
.knowledge-empty { background: var(--paper); border: 1px solid var(--line-strong); }
@media (max-width: 760px) { .knowledge-search { grid-template-columns: 1fr; }.knowledge-search .button { width: 100%; } }
</style>
