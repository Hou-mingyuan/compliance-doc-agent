<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, streamAudit, type AuditFinding } from "../api";
import { buildHighlightedHtml, collectHighlightSpans } from "../highlight";
import { showToast } from "../toast";

const route = useRoute();
const router = useRouter();

const documentId = computed(() => (route.query.documentId as string) || "");
const filename = computed(() => (route.query.filename as string) || docTitle.value || "未命名文档");
const auditId = ref("");
const streaming = ref(false);
const loadingReport = ref(false);
const loadingDoc = ref(false);
const narrative = ref("");
const findings = ref<AuditFinding[]>([]);
const summary = ref("");
const docContent = ref("");
const docTitle = ref("");
const activeMatchStart = ref<number | null>(null);
const recentMatchStart = ref<number | null>(null);
const streamPhase = ref<"idle" | "connect" | "rules" | "narrative" | "summary" | "done">("idle");
const narrativeEl = ref<HTMLElement | null>(null);
const docPreviewEl = ref<HTMLElement | null>(null);
const streamLogEl = ref<HTMLElement | null>(null);
const tokenCount = ref(0);

interface StreamEvent {
  time: string;
  type: string;
  label: string;
}
const streamLog = ref<StreamEvent[]>([]);
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
  () =>
    streaming.value ||
    loadingReport.value ||
    narrative.value ||
    findings.value.length > 0 ||
    summary.value ||
    docContent.value,
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

const highlightedHtml = computed(() => {
  const activeIdx =
    activeMatchStart.value == null
      ? -1
      : collectHighlightSpans(findings.value).findIndex((s) => s.start === activeMatchStart.value);
  return buildHighlightedHtml(docContent.value, findings.value, activeIdx, recentMatchStart.value);
});

const phaseSteps = [
  { key: "connect", label: "连接" },
  { key: "rules", label: "规则扫描" },
  { key: "narrative", label: "AI 分析" },
  { key: "summary", label: "摘要" },
  { key: "done", label: "完成" },
];

function phaseIndex(key: string) {
  return phaseSteps.findIndex((s) => s.key === key);
}

function isPhaseDone(key: string) {
  const cur = phaseIndex(streamPhase.value);
  const idx = phaseIndex(key);
  return cur > idx || streamPhase.value === "done";
}

function isPhaseActive(key: string) {
  return streamPhase.value === key;
}

function pushLog(type: string, label: string) {
  const now = new Date();
  const time = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}:${String(now.getSeconds()).padStart(2, "0")}`;
  streamLog.value.push({ time, type, label });
  if (streamLog.value.length > 48) {
    streamLog.value = streamLog.value.slice(-48);
  }
  void scrollStreamLog();
}

async function scrollStreamLog() {
  await nextTick();
  const el = streamLogEl.value;
  if (el) el.scrollTop = el.scrollHeight;
}

function notifyError(msg: string) {
  showToast(msg, "error");
}

async function scrollNarrative() {
  await nextTick();
  narrativeEl.value?.scrollTo({ top: narrativeEl.value.scrollHeight, behavior: "smooth" });
}

async function loadDocumentContent() {
  if (!documentId.value) return;
  loadingDoc.value = true;
  try {
    const doc = await api.getDocument(documentId.value);
    docContent.value = doc.content;
    docTitle.value = doc.title;
  } catch (e: unknown) {
    notifyError(e instanceof Error ? e.message : "加载文档正文失败");
  } finally {
    loadingDoc.value = false;
  }
}

async function startStream() {
  if (!documentId.value || streaming.value) return;
  streaming.value = true;
  streamPhase.value = "connect";
  narrative.value = "";
  findings.value = [];
  summary.value = "";
  auditId.value = "";
  streamLog.value = [];
  tokenCount.value = 0;
  activeMatchStart.value = null;
  recentMatchStart.value = null;

  await loadDocumentContent();
  pushLog("start", "开始 SSE 审核流");

  controller = new AbortController();
  try {
    await streamAudit(
      { documentId: documentId.value },
      (event, data) => {
        const d = data as Record<string, unknown>;
        if (event === "start") {
          auditId.value = String(d.auditId ?? "");
          streamPhase.value = "rules";
          pushLog("start", `审核 ID ${auditId.value}`);
        } else if (event === "finding") {
          streamPhase.value = "rules";
          const f = d as unknown as AuditFinding;
          findings.value.push(f);
          pushLog("finding", `${severityLabel[f.severity]} · ${f.rule}`);
          if (f.matchStart != null && f.matchStart >= 0) {
            recentMatchStart.value = f.matchStart;
            activeMatchStart.value = f.matchStart;
            void nextTick(() => {
              const mark = docPreviewEl.value?.querySelector(`mark[data-start="${f.matchStart}"]`);
              mark?.scrollIntoView({ behavior: "smooth", block: "nearest" });
            });
          }
        } else if (event === "token" || event === "narrative") {
          streamPhase.value = "narrative";
          const text = String(d.text ?? d.content ?? "");
          narrative.value += text;
          tokenCount.value += text.length;
          scrollNarrative();
        } else if (event === "summary") {
          streamPhase.value = "summary";
          summary.value = String(d.text ?? d.summary ?? "");
          pushLog("summary", "生成审核摘要");
        } else if (event === "done") {
          streamPhase.value = "done";
          auditId.value = String(d.auditId ?? auditId.value);
          if (d.summary) summary.value = String(d.summary);
          pushLog("done", "审核完成");
          showToast("审核完成", "success", 2600);
        } else if (event === "error") {
          notifyError(String(d.message ?? "审核失败"));
        }
      },
      controller.signal,
    );
  } catch (e: unknown) {
    if (e instanceof Error && e.name !== "AbortError") {
      notifyError(e.message);
    }
  } finally {
    streaming.value = false;
    if (streamPhase.value === "connect" || streamPhase.value === "rules" || streamPhase.value === "narrative" || streamPhase.value === "summary") {
      streamPhase.value = "idle";
    }
  }
}

function stopStream() {
  controller?.abort();
  streaming.value = false;
  showToast("已停止审核流", "info", 2200);
}

async function loadExistingReport() {
  const id = route.params.id as string;
  if (!id) return;
  loadingReport.value = true;
  try {
    const report = await api.getReport(id);
    auditId.value = report.id;
    narrative.value = report.narrative;
    findings.value = report.findings;
    summary.value = report.summary ?? "";
    if (report.documentId) {
      const doc = await api.getDocument(report.documentId);
      docContent.value = doc.content;
      docTitle.value = doc.title;
    }
  } catch (e: unknown) {
    notifyError(e instanceof Error ? e.message : "加载报告失败");
  } finally {
    loadingReport.value = false;
  }
}

function selectFinding(f: AuditFinding) {
  activeMatchStart.value = f.matchStart != null && f.matchStart >= 0 ? f.matchStart : null;
  if (activeMatchStart.value != null && docPreviewEl.value) {
    const mark = docPreviewEl.value.querySelector(`mark[data-start="${activeMatchStart.value}"]`);
    mark?.scrollIntoView({ behavior: "smooth", block: "center" });
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
  <div class="workbench">
    <header class="wb-head">
      <div>
        <h1 class="title">审核工作台</h1>
        <p class="desc">
          {{ filename }}
          <span v-if="auditId" class="audit-id"> · {{ auditId.slice(0, 8) }}…</span>
        </p>
      </div>
      <div class="actions">
        <button v-if="streaming" class="btn btn-ghost" @click="stopStream">停止</button>
        <button v-else-if="documentId" class="btn btn-primary" :disabled="loadingReport" @click="startStream">
          重新审核
        </button>
        <button class="btn btn-ghost" @click="router.push('/upload')">返回上传</button>
      </div>
    </header>

    <div v-if="!documentId && !route.params.id" class="empty card">
      <div class="empty-state">
        <div class="empty-state-icon">📋</div>
        <p class="empty-state-title">尚未选择审核文档</p>
        <p class="empty-state-desc">请先从上传页选择合规材料并点击「开始审核」。</p>
        <button class="btn btn-primary" @click="router.push('/upload')">前往上传</button>
      </div>
    </div>

    <template v-else>
      <div v-if="streaming || streamPhase === 'done'" class="phase-bar card">
        <div
          v-for="s in phaseSteps"
          :key="s.key"
          class="phase-step"
          :class="{ active: isPhaseActive(s.key), done: isPhaseDone(s.key) }"
        >
          <span class="phase-dot">{{ isPhaseDone(s.key) ? "✓" : "·" }}</span>
          {{ s.label }}
        </div>
        <span v-if="streaming" class="phase-live">
          <span class="spinner spinner-sm"></span>
          SSE 流式中
          <template v-if="streamPhase === 'rules' && findings.length"> · {{ findings.length }} 项命中</template>
          <template v-else-if="streamPhase === 'narrative' && tokenCount"> · {{ tokenCount }} 字</template>
        </span>
      </div>

      <div class="wb-grid">
        <section class="panel card doc-panel">
          <div class="panel-head">
            <h2>文档预览 · Diff 高亮</h2>
            <span class="panel-meta">{{ docContent.length }} 字</span>
          </div>
          <div v-if="loadingDoc" class="panel-loading"><span class="spinner"></span>加载正文…</div>
          <div
            v-else-if="docContent"
            ref="docPreviewEl"
            class="doc-preview"
            v-html="highlightedHtml"
          ></div>
          <p v-else class="panel-empty">暂无文档正文</p>
          <p class="panel-tip">命中条款以颜色高亮；点击右侧风险项可定位到对应片段</p>
        </section>

        <section class="panel card stream-panel">
          <div class="panel-head">
            <h2>SSE 事件流</h2>
            <span v-if="tokenCount" class="panel-meta">{{ tokenCount }} 字符</span>
          </div>
          <ul v-if="streamLog.length" ref="streamLogEl" class="event-log">
            <li v-for="(e, i) in streamLog" :key="i" :class="`ev-${e.type}`">
              <time>{{ e.time }}</time>
              <span class="ev-type">{{ e.type }}</span>
              <span>{{ e.label }}</span>
            </li>
          </ul>
          <p v-else class="panel-empty">等待审核事件…</p>

          <div v-if="summary && !loadingReport" class="inline-summary">
            <b>摘要</b>
            <p>{{ summary }}</p>
          </div>

          <div v-if="(narrative || streaming) && !loadingReport" class="narrative-block">
            <b>详细分析</b>
            <div ref="narrativeEl" class="narrative-scroll">
              <p v-if="narrative">{{ narrative }}<span v-if="streaming" class="cursor">▋</span></p>
              <div v-else-if="streaming" class="panel-loading"><span class="spinner spinner-sm"></span>生成中…</div>
            </div>
          </div>
        </section>

        <section class="panel card findings-panel">
          <div class="panel-head">
            <h2>风险项</h2>
            <span class="panel-meta">{{ sortedFindings.length }} 项</span>
          </div>

          <div v-if="severityStats.length" class="risk-chips">
            <span
              v-for="s in severityStats"
              :key="s.severity"
              :class="['risk-chip', `risk-chip-${s.severity}`]"
            >
              {{ s.label }} <strong>{{ s.count }}</strong>
            </span>
          </div>

          <ul v-if="sortedFindings.length" class="finding-list">
            <li
              v-for="(f, i) in sortedFindings"
              :key="i"
              :class="['finding-item', `finding-${f.severity}`, { active: activeMatchStart === f.matchStart && f.matchStart != null && f.matchStart >= 0 }]"
              @click="selectFinding(f)"
            >
              <div class="finding-head">
                <span :class="['badge', `badge-${f.severity}`]">{{ severityLabel[f.severity] }}</span>
                <span v-if="f.kind === 'missing'" class="missing-tag">缺失</span>
                <strong>{{ f.rule }}</strong>
              </div>
              <p>{{ f.description }}</p>
              <blockquote v-if="f.matchedText" class="quote-block match-quote">
                <span class="quote-block-label">命中片段</span>
                {{ f.matchedText }}
              </blockquote>
              <blockquote v-else-if="f.location" class="quote-block">
                <span class="quote-block-label">规则</span>
                {{ f.location }}
              </blockquote>
            </li>
          </ul>
          <p v-else-if="!streaming && !loadingReport" class="panel-empty">暂无风险项</p>
        </section>
      </div>

      <div v-if="loadingReport" class="loading-overlay card">
        <span class="spinner"></span> 正在加载历史报告…
      </div>
    </template>
  </div>
</template>

<style scoped>
.workbench { max-width: 1280px; margin: 0 auto; }
.wb-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.title { margin: 0 0 4px; font-size: 22px; }
.desc { margin: 0; color: var(--muted); font-size: 13px; }
.audit-id { font-family: ui-monospace, monospace; }
.actions { display: flex; gap: 8px; flex-shrink: 0; }

.phase-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 12px 16px;
  margin-bottom: 16px;
}
.phase-step {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}
.phase-step.done { color: var(--success); }
.phase-step.active { color: var(--primary); }
.phase-dot {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #f1f5f9;
  display: grid;
  place-items: center;
  font-size: 10px;
}
.phase-step.active .phase-dot { background: var(--primary-soft); }
.phase-step.done .phase-dot { background: #dcfce7; }
.phase-live {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--primary);
  font-weight: 600;
}

.wb-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: auto auto;
  gap: 16px;
}
.doc-panel { grid-column: 1; grid-row: 1 / 3; }
.findings-panel { grid-column: 2; grid-row: 1; }
.stream-panel { grid-column: 2; grid-row: 2; }

.panel { padding: 16px; display: flex; flex-direction: column; min-height: 0; }
.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}
.panel-head h2 { margin: 0; font-size: 15px; }
.panel-meta { font-size: 12px; color: var(--muted); font-weight: 600; }
.panel-empty { color: var(--muted); font-size: 13px; margin: 8px 0; }
.panel-tip { font-size: 11px; color: var(--muted); margin: 10px 0 0; }
.panel-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  padding: 20px 0;
}

.event-log {
  list-style: none;
  padding: 0;
  margin: 0 0 12px;
  max-height: 160px;
  overflow-y: auto;
  font-size: 12px;
}
.event-log li {
  display: flex;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 8px;
  margin-bottom: 4px;
  background: #f8fafc;
}
.event-log time { color: var(--muted); font-family: ui-monospace, monospace; flex-shrink: 0; }
.event-log .ev-type {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  background: #e2e8f0;
  color: #475569;
}
.ev-finding { border-left: 3px solid var(--warn); }
.ev-finding .ev-type { background: #fef3c7; color: #92400e; }
.ev-done { border-left: 3px solid var(--success); }
.ev-done .ev-type { background: #dcfce7; color: #166534; }
.ev-error { border-left: 3px solid var(--danger); }
.ev-error .ev-type { background: #fee2e2; color: #991b1b; }

.inline-summary {
  padding: 12px;
  background: var(--primary-soft);
  border-radius: 10px;
  margin-bottom: 12px;
  font-size: 13px;
}
.inline-summary p { margin: 6px 0 0; line-height: 1.6; }

.narrative-block b { font-size: 13px; }
.narrative-scroll {
  margin-top: 8px;
  max-height: 200px;
  overflow-y: auto;
  padding: 12px;
  background: #f8fafc;
  border-radius: 10px;
  border: 1px solid var(--border);
  font-size: 13px;
  line-height: 1.7;
}
.narrative-scroll p { margin: 0; white-space: pre-wrap; word-break: break-word; }
.cursor { animation: blink 0.8s step-end infinite; }
@keyframes blink { 50% { opacity: 0; } }

.risk-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.risk-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid transparent;
}
.risk-chip-high { background: var(--risk-high-bg); color: var(--risk-high); border-color: var(--risk-high-border); }
.risk-chip-medium { background: var(--risk-medium-bg); color: var(--risk-medium); border-color: var(--risk-medium-border); }
.risk-chip-low { background: var(--risk-low-bg); color: var(--risk-low); border-color: var(--risk-low-border); }
.risk-chip-info { background: var(--risk-info-bg); color: var(--risk-info); border-color: var(--risk-info-border); }
.risk-chip-critical { background: var(--risk-critical-bg); color: var(--risk-critical); border-color: var(--risk-critical-border); }

.finding-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; max-height: 420px; overflow-y: auto; }
.finding-item {
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--border);
  border-left-width: 4px;
  cursor: pointer;
  transition: box-shadow 0.15s;
}
.finding-item:hover { box-shadow: var(--shadow); }
.finding-item.active { box-shadow: 0 0 0 2px var(--primary); }
.finding-critical { border-left-color: var(--risk-critical); }
.finding-high { border-left-color: var(--risk-high); }
.finding-medium { border-left-color: var(--risk-medium); }
.finding-low { border-left-color: var(--risk-low); }
.finding-info { border-left-color: var(--risk-info-border); }
.finding-head { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.finding-item p { margin: 0; font-size: 13px; line-height: 1.55; }
.missing-tag {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
  background: #f1f5f9;
  color: var(--muted);
}
.match-quote { margin-top: 8px; background: #fff7ed; border-left-color: var(--warn); }

.loading-overlay {
  margin-top: 16px;
  padding: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--muted);
}
.spinner-sm { width: 14px; height: 14px; border-width: 2px; }
.empty { padding: 20px; }

@media (max-width: 960px) {
  .wb-grid { grid-template-columns: 1fr; }
  .doc-panel, .findings-panel, .stream-panel { grid-column: 1; grid-row: auto; }
}
</style>
