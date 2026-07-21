<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { AlertTriangle, FileSearch, RefreshCw, Search } from "@lucide/vue";
import { api, type ReviewRecord } from "../api";
import { reviewStatusLabel, formatDateTime, shortKey, statusClass } from "../ui";

const router = useRouter();
const reviews = ref<ReviewRecord[]>([]);
const loading = ref(true);
const error = ref("");
const query = ref("");
const status = ref("ALL");
let controller: AbortController | null = null;

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  return reviews.value.filter((review) => {
    const matchesStatus = status.value === "ALL" || review.status === status.value;
    const matchesText = !keyword
      || review.reviewKey.toLowerCase().includes(keyword)
      || String(review.documentId).includes(keyword)
      || review.createdBy.toLowerCase().includes(keyword);
    return matchesStatus && matchesText;
  });
});
const active = computed(() => reviews.value.filter((item) =>
  ["CREATED", "RUNNING", "REMEDIATION", "RECHECK"].includes(item.status)).length);
const awaiting = computed(() => reviews.value.filter((item) => item.status === "PENDING_REVIEW").length);
const highRisk = computed(() => reviews.value.filter((item) => item.riskScore >= 60).length);

async function load() {
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    reviews.value = await api.listReviews(controller.signal);
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === "AbortError") return;
    error.value = reason instanceof Error ? reason.message : "审核列表加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <div class="workspace-page">
    <header class="page-heading">
      <div>
        <span class="eyebrow">Review ledger</span>
        <h1>审核运行</h1>
        <p>每次规则检查、工具执行和人工判断都归入一条可追溯运行。</p>
      </div>
      <button class="button secondary" type="button" :disabled="loading" @click="load">
        <RefreshCw :size="16" :class="{ rotating: loading }" /> 刷新
      </button>
    </header>

    <section class="metric-strip" aria-label="审核统计">
      <div><span>全部运行</span><strong>{{ reviews.length }}</strong></div>
      <div><span>正在处理</span><strong>{{ active }}</strong></div>
      <div><span>等待人工复核</span><strong>{{ awaiting }}</strong></div>
      <div><span>风险分 ≥ 60</span><strong>{{ highRisk }}</strong></div>
    </section>

    <section class="register-section">
      <div class="register-toolbar">
        <div class="search-control">
          <Search :size="16" aria-hidden="true" />
          <input v-model="query" type="search" placeholder="搜索运行编号、文档 ID 或创建人" aria-label="搜索审核运行" />
        </div>
        <select v-model="status" class="filter-select" aria-label="按审核状态筛选">
          <option value="ALL">全部状态</option>
          <option v-for="(label, value) in reviewStatusLabel" :key="value" :value="value">{{ label }}</option>
        </select>
        <span class="result-count">{{ filtered.length }} 条</span>
      </div>

      <div v-if="loading" class="table-state" aria-busy="true"><span class="spinner"></span>读取审核运行</div>
      <div v-else-if="error" class="error-state" role="alert">
        <AlertTriangle :size="22" />
        <strong>审核运行加载失败</strong><span>{{ error }}</span>
        <button class="button secondary" type="button" @click="load">重试</button>
      </div>
      <div v-else-if="!filtered.length" class="empty-state">
        <FileSearch :size="34" />
        <strong>{{ reviews.length ? "没有符合筛选条件的运行" : "尚无审核运行" }}</strong>
        <span>{{ reviews.length ? "修改搜索条件后重试。" : "从文档台账发起第一次真实审核。" }}</span>
        <button v-if="!reviews.length" class="button primary" type="button" @click="router.push('/documents')">前往文档台账</button>
      </div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead><tr><th>审核运行</th><th>文档</th><th>状态</th><th>风险</th><th>规则 / 模型</th><th>更新时间</th><th class="action-col">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in filtered" :key="item.reviewKey">
              <td data-label="审核运行"><strong class="mono">{{ shortKey(item.reviewKey, 16) }}</strong><small>{{ item.createdBy }}</small></td>
              <td data-label="文档"><strong>DOC-{{ String(item.documentId).padStart(5, "0") }}</strong><small>{{ item.tenantId }}</small></td>
              <td data-label="状态"><span :class="['status-chip', statusClass(item.status)]">{{ reviewStatusLabel[item.status] || item.status }}</span></td>
              <td data-label="风险">
                <strong :class="['risk-score', { elevated: item.riskScore >= 60 }]">{{ item.riskScore }}</strong>
                <small>/ 100</small>
              </td>
              <td data-label="规则 / 模型"><strong>{{ item.rulePackVersion }}</strong><small>{{ item.llmProvider }}</small></td>
              <td data-label="更新时间">{{ formatDateTime(item.updatedAt) }}</td>
              <td data-label="操作" class="row-actions">
                <button class="button secondary small" type="button" @click="router.push(`/reviews/${item.reviewKey}`)">打开工作台</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.risk-score { font-family: var(--font-display); font-size: 18px; }
.risk-score.elevated { color: var(--red-700); }
.data-table td small { display: block; margin-top: 3px; color: var(--ink-500); font-size: 10px; }
</style>
