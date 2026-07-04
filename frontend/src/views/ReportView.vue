<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, streamAudit, type AuditFinding } from "../api";

const route = useRoute();
const router = useRouter();

const documentId = computed(() => (route.query.documentId as string) || "");
const filename = computed(() => (route.query.filename as string) || "未命名文档");
const auditId = ref("");
const streaming = ref(false);
const loadingReport = ref(false);
const narrative = ref("");
const findings = ref<AuditFinding[]>([]);
const summary = ref("");
const error = ref("");
const narrativeEl = ref<HTMLElement | null>(null);
let controller: AbortController | null = null;

const severityLabel: Record<AuditFinding["severity"], string> = {
  critical: "严重",
  high: "高",
  medium: "中",
  low: "低",
  info: "提示",
};

const severityOrder: AuditFinding["severity"][] = ["critical", "high", "medium", "low", "info"];

const hasContent = computed(
  () => streaming.value || loadingReport.value || narrative.value || findings.value.length > 0 || summary.value,
);

const severityStats = computed(() => {
  const counts: Record<string, number> = { critical: 0, high: 0, medium: 0, low: 0, info: 0 };
  for (const f of findings.value) counts[f.severity] = (counts[f.severity] ?? 0) + 1;
  return severityOrder
    .filter((s) => counts[s] > 0)
    .map((s) => ({ severity: s, label: severityLabel[s], count: counts[s] }));
});

const sortedFindings = computed(() =>
  [...findings.value].sort(
    (a, b) => severityOrder.indexOf(a.severity) - severityOrder.indexOf(b.severity),
  ),
);

async function scrollNarrative() {
  await nextTick();
  if (narrativeEl.value) {
    narrativeEl.value.scrollTop = narrativeEl.value.scrollHeight;
  }
}

async function startStream() {
  if (!documentId.value || streaming.value) return;
  streaming.value = true;
  error.value = "";
  narrative.value = "";
  findings.value = [];
  summary.value = "";
  auditId.value = "";

  controller = new AbortController();
  try {
    await streamAudit(
      { documentId: documentId.value },
      (event, data) => {
        const d = data as Record<string, unknown>;
        if (event === "start") {
          auditId.value = String(d.auditId ?? "");
        } else if (event === "finding") {
          findings.value.push(d as unknown as AuditFinding);
        } else if (event === "token") {
          narrative.value += String(d.text ?? d.content ?? "");
          scrollNarrative();
        } else if (event === "summary") {
          summary.value = String(d.text ?? d.summary ?? "");
        } else if (event === "done") {
          auditId.value = String(d.auditId ?? auditId.value);
          if (d.summary) summary.value = String(d.summary);
        } else if (event === "error") {
          error.value = String(d.message ?? "审核失败");
        }
      },
      controller.signal,
    );
  } catch (e: unknown) {
    if (e instanceof Error && e.name !== "AbortError") {
      error.value = e.message;
    }
  } finally {
    streaming.value = false;
  }
}

function stopStream() {
  controller?.abort();
  streaming.value = false;
}

async function loadExistingReport() {
  const id = route.params.id as string;
  if (!id) return;
  loadingReport.value = true;
  error.value = "";
  try {
    const report = await api.getReport(id);
    auditId.value = report.id;
    narrative.value = report.narrative;
    findings.value = report.findings;
    summary.value = report.summary ?? "";
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : "加载报告失败";
  } finally {
    loadingReport.value = false;
  }
}

onMounted(async () => {
  if (route.params.id) {
    await loadExistingReport();
  } else if (documentId.value) {
    await startStream();
  }
});

onUnmounted(() => controller?.abort());
</script>

<template>
  <div class="page">
    <div class="header">
      <div>
        <h1 class="title">审核报告</h1>
        <p class="desc">
          <span v-if="filename">{{ filename }}</span>
          <span v-if="auditId" class="audit-id"> · 报告 ID: {{ auditId }}</span>
        </p>
      </div>
      <div class="actions">
        <button v-if="streaming" class="btn btn-ghost" @click="stopStream">停止</button>
        <button v-else-if="documentId" class="btn btn-primary" :disabled="loadingReport" @click="startStream">
          重新审核
        </button>
        <button class="btn btn-ghost" @click="router.push('/upload')">返回上传</button>
      </div>
    </div>

    <div v-if="!documentId && !route.params.id" class="empty card">
      <div class="empty-state">
        <div class="empty-state-icon">📋</div>
        <p class="empty-state-title">尚未选择审核文档</p>
        <p class="empty-state-desc">请先从上传页选择合规材料并点击「开始审核」，报告将在此流式展示。</p>
        <button class="btn btn-primary" @click="router.push('/upload')">前往上传</button>
      </div>
    </div>

    <p v-if="error" class="error-banner">{{ error }}</p>

    <div v-if="streaming" class="streaming-bar">
      <span class="spinner"></span>
      <span>AI 正在流式生成审核报告…</span>
      <span class="streaming-hint">风险项会实时追加到下方列表</span>
    </div>

    <div v-if="loadingReport" class="loading-panel card">
      <div class="loading-row"><span class="spinner"></span>正在加载历史报告…</div>
      <div class="skeleton skeleton-block"></div>
      <div class="skeleton skeleton-block short"></div>
    </div>

    <div v-if="summary && !loadingReport" class="summary card">
      <h2>审核摘要</h2>
      <p class="summary-text">{{ summary }}</p>
    </div>

    <div v-if="severityStats.length && !loadingReport" class="risk-stats card">
      <span class="risk-stats-label">风险分布</span>
      <div class="risk-stats-chips">
        <span
          v-for="s in severityStats"
          :key="s.severity"
          :class="['risk-chip', `risk-chip-${s.severity}`]"
        >
          {{ s.label }} <strong>{{ s.count }}</strong>
        </span>
      </div>
    </div>

    <div v-if="sortedFindings.length && !loadingReport" class="findings card">
      <h2>风险项（{{ sortedFindings.length }}）</h2>
      <ul class="finding-list">
        <li
          v-for="(f, i) in sortedFindings"
          :key="i"
          :class="['finding-item', `finding-${f.severity}`]"
        >
          <div class="finding-head">
            <span :class="['badge', `badge-${f.severity}`]">{{ severityLabel[f.severity] }}</span>
            <strong class="finding-rule">{{ f.rule }}</strong>
          </div>
          <p class="finding-desc">{{ f.description }}</p>
          <blockquote v-if="f.location" class="quote-block">
            <span class="quote-block-label">条款引用 / 位置</span>
            {{ f.location }}
          </blockquote>
        </li>
      </ul>
    </div>

    <div v-if="(narrative || streaming) && !loadingReport" class="narrative card">
      <h2>详细分析</h2>
      <div ref="narrativeEl" class="narrative-body">
        <p v-if="narrative">{{ narrative }}<span v-if="streaming" class="cursor">▌</span></p>
        <div v-else-if="streaming" class="narrative-loading">
          <span class="spinner"></span>
          <span>正在生成分析正文…</span>
        </div>
      </div>
    </div>

    <div
      v-else-if="documentId && !streaming && !loadingReport && !error && !findings.length && !summary"
      class="empty card"
    >
      <div class="empty-state">
        <div class="empty-state-icon">⏳</div>
        <p class="empty-state-title">等待审核结果</p>
        <p class="empty-state-desc">若长时间无响应，请检查后端服务或点击「重新审核」。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { max-width: 960px; margin: 0 auto; }
.header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.title { margin: 0 0 4px; font-size: 22px; }
.desc { margin: 0; color: var(--muted); font-size: 13px; }
.audit-id { font-family: ui-monospace, monospace; }
.actions { display: flex; gap: 8px; flex-shrink: 0; }
.error-banner {
  padding: 12px 16px;
  background: #fee2e2;
  color: #991b1b;
  border-radius: var(--radius);
  margin-bottom: 16px;
}
.streaming-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  padding: 12px 16px;
  background: var(--primary-soft);
  color: var(--primary);
  border-radius: var(--radius);
  font-weight: 600;
  margin-bottom: 16px;
}
.streaming-hint { font-weight: 500; font-size: 12px; color: var(--muted); margin-left: auto; }
.loading-panel { padding: 20px; margin-bottom: 16px; }
.loading-row {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  margin-bottom: 16px;
}
.skeleton-block { height: 72px; margin-bottom: 12px; }
.skeleton-block.short { height: 40px; width: 60%; }
.summary, .findings, .narrative, .risk-stats, .empty { padding: 20px; margin-bottom: 16px; }
.summary h2, .findings h2, .narrative h2 {
  margin: 0 0 12px;
  font-size: 15px;
}
.summary-text { margin: 0; line-height: 1.75; color: var(--text); }
.risk-stats {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.risk-stats-label { font-size: 13px; font-weight: 700; color: var(--muted); }
.risk-stats-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.risk-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid transparent;
}
.risk-chip-critical { background: var(--risk-critical-bg); color: var(--risk-critical); border-color: var(--risk-critical-border); }
.risk-chip-high { background: var(--risk-high-bg); color: var(--risk-high); border-color: var(--risk-high-border); }
.risk-chip-medium { background: var(--risk-medium-bg); color: var(--risk-medium); border-color: var(--risk-medium-border); }
.risk-chip-low { background: var(--risk-low-bg); color: var(--risk-low); border-color: var(--risk-low-border); }
.risk-chip-info { background: var(--risk-info-bg); color: var(--risk-info); border-color: var(--risk-info-border); }
.finding-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 12px; }
.finding-item {
  padding: 14px 16px;
  border-radius: 10px;
  border: 1px solid var(--border);
  border-left-width: 4px;
  background: var(--panel);
}
.finding-critical { border-left-color: var(--risk-critical); background: var(--risk-critical-bg); }
.finding-high { border-left-color: var(--risk-high); background: var(--risk-high-bg); }
.finding-medium { border-left-color: var(--risk-medium); background: var(--risk-medium-bg); }
.finding-low { border-left-color: var(--risk-low); background: var(--risk-low-bg); }
.finding-info { border-left-color: var(--risk-info-border); background: var(--risk-info-bg); }
.finding-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.finding-rule { font-size: 15px; }
.finding-desc { margin: 0; line-height: 1.65; }
.narrative-body {
  margin: 0;
  max-height: 480px;
  overflow-y: auto;
  padding: 14px 16px;
  background: #f8fafc;
  border-radius: 10px;
  border: 1px solid var(--border);
}
.narrative-body p {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.75;
}
.narrative-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
}
.cursor { animation: blink 0.8s step-end infinite; }
@keyframes blink { 50% { opacity: 0; } }
</style>
