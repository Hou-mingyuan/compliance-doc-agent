<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  AlertTriangle,
  BookOpenCheck,
  Check,
  CircleStop,
  Download,
  FileCheck2,
  FileSearch,
  FileText,
  ListRestart,
  Play,
  RefreshCw,
  ShieldAlert,
  Sparkles,
  Wrench,
  X,
} from "@lucide/vue";
import {
  api,
  streamAudit,
  type Assignee,
  type DocumentContent,
  type Finding,
  type ReportMetadata,
  type ReviewDetail,
  type StreamFinding,
} from "../api";
import { hasRole, useAuth } from "../auth";
import { buildHighlightedHtml, type HighlightFinding } from "../highlight";
import { showToast } from "../toast";
import {
  entityTypeLabel,
  findingStatusLabel,
  formatBytes,
  formatDateTime,
  formatSourceLocation,
  localizeRiskSummary,
  remediationStatusLabel,
  reviewStatusLabel,
  severityClass,
  severityLabel,
  statusClass,
} from "../ui";

interface LiveTool {
  name: string;
  ok: boolean;
  code: string;
  summary: string;
}

const route = useRoute();
const router = useRouter();
const { state: authState } = useAuth();
const detail = ref<ReviewDetail | null>(null);
const document = ref<DocumentContent | null>(null);
const loading = ref(true);
const error = ref("");
const streaming = ref(false);
const streamError = ref("");
const liveReviewKey = ref("");
const liveFindings = ref<StreamFinding[]>([]);
const liveTools = ref<LiveTool[]>([]);
const narrative = ref("");
const liveSummary = ref("");
const liveRiskScore = ref(0);
const stage = ref("IDLE");
const stageMessage = ref("");
const selectedFinding = ref("");
const busyAction = ref("");
const reviewComments = ref<Record<string, string>>({});
const approvalComment = ref("");
const taskFinding = ref<Finding | null>(null);
const taskDescription = ref("");
const taskAssignee = ref("");
const taskDueDate = ref("");
const assignees = ref<Assignee[]>([]);
const documentPanel = ref<HTMLElement | null>(null);
let streamController: AbortController | null = null;
let loadController: AbortController | null = null;

const newDocumentId = computed(() => String(route.params.documentId || ""));
const routeReviewKey = computed(() => String(route.params.reviewKey || ""));
const review = computed(() => detail.value?.review ?? null);
const reviewKey = computed(() => review.value?.reviewKey || liveReviewKey.value);
const persistedFindings = computed(() => detail.value?.findings ?? []);
const highlightedFindings = computed<HighlightFinding[]>(() => {
  if (persistedFindings.value.length) {
    return persistedFindings.value.map(({ finding }) => ({
      severity: finding.severity.toLowerCase() as HighlightFinding["severity"],
      rule: finding.ruleCode || finding.title,
      description: finding.description,
      kind: finding.matchStart == null ? "missing" : "hit",
      matchStart: finding.matchStart,
      matchEnd: finding.matchEnd,
    }));
  }
  return liveFindings.value.map((finding) => ({
    severity: finding.severity,
    rule: finding.location || finding.rule,
    description: finding.description,
    kind: finding.kind,
    matchStart: finding.matchStart,
    matchEnd: finding.matchEnd,
  }));
});
const activeHighlightIndex = computed(() => {
  if (!selectedFinding.value || !persistedFindings.value.length) return -1;
  return persistedFindings.value.findIndex(({ finding }) => finding.findingKey === selectedFinding.value);
});
const highlightedHtml = computed(() =>
  buildHighlightedHtml(document.value?.content || "", highlightedFindings.value, activeHighlightIndex.value));
const displayRiskScore = computed(() => review.value?.riskScore ?? liveRiskScore.value);
const reportReady = computed(() =>
  Boolean(review.value && !["CREATED", "RUNNING"].includes(review.value.status) && hasRole("REVIEWER")));
const canApprove = computed(() =>
  Boolean(review.value && ["PENDING_REVIEW", "RECHECK"].includes(review.value.status) && hasRole("REVIEWER")));
const allFindingsReviewed = computed(() =>
  persistedFindings.value.every(({ finding }) => ["FALSE_POSITIVE", "RESOLVED"].includes(finding.status)));
const terminal = computed(() =>
  Boolean(review.value && ["APPROVED", "CANCELLED", "FAILED"].includes(review.value.status)));
const displayTools = computed(() => detail.value?.toolExecutions.map((tool) => ({
  id: tool.executionKey,
  name: tool.toolName,
  ok: tool.success,
  summary: localizeRiskSummary(tool.summary),
  tail: `${tool.durationMs} ms`,
})) ?? liveTools.value.map((tool, index) => ({
  id: `live-${index}-${tool.name}`,
  name: tool.name,
  ok: tool.ok,
  summary: localizeRiskSummary(tool.summary),
  tail: tool.code,
})));
const phases = computed(() => [
  ["PREPARE", "准备上下文"],
  ["RULES", "规则 / 法规"],
  ["ENTITIES", "实体抽取"],
  ...(document.value?.versionNo && document.value.versionNo > 1
    ? [["COMPARE", "版本比较"] as const]
    : []),
  ["SUMMARY", "风险汇总"],
  ["NARRATIVE", "结果说明"],
  ["DONE", "持久化"],
] as const);
const displaySummary = computed(() => localizeRiskSummary(
  review.value?.summary || liveSummary.value ||
  (streaming.value ? "正在从真实工具结果整理摘要…" : "尚未生成审核摘要。"),
));

function phaseState(key: string) {
  const current = phases.value.findIndex(([phase]) => phase === stage.value);
  const target = phases.value.findIndex(([phase]) => phase === key);
  if (stage.value === "DONE") return "done";
  if (current === target) return "active";
  if (current > target) return "done";
  return "pending";
}

async function loadDetail(key = routeReviewKey.value || reviewKey.value) {
  if (!key) return;
  loadController?.abort();
  loadController = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    detail.value = await api.getReview(key, loadController.signal);
    document.value = detail.value.document;
    liveReviewKey.value = detail.value.review.reviewKey;
    if (hasRole("COMPLIANCE_ADMIN")) {
      const tenant = authState.user?.role === "SYSTEM_ADMIN" ? detail.value.review.tenantId : undefined;
      assignees.value = await api.listAssignees(tenant, loadController.signal);
    }
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === "AbortError") return;
    error.value = reason instanceof Error ? reason.message : "审核详情加载失败";
  } finally {
    loading.value = false;
  }
}

async function loadNewDocument() {
  if (!newDocumentId.value) return;
  loading.value = true;
  error.value = "";
  try {
    const reviews = await api.listReviews();
    const existing = reviews
      .filter((item) => String(item.documentId) === newDocumentId.value)
      .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))[0];
    if (existing && !["CANCELLED", "FAILED"].includes(existing.status)) {
      await loadDetail(existing.reviewKey);
      await router.replace(`/reviews/${existing.reviewKey}`);
      return;
    }
    document.value = await api.getDocument(newDocumentId.value);
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "文档正文加载失败";
  } finally {
    loading.value = false;
  }
}

async function startReview() {
  if (!document.value || streaming.value) return;
  streamController?.abort();
  streamController = new AbortController();
  streaming.value = true;
  streamError.value = "";
  liveReviewKey.value = "";
  liveFindings.value = [];
  liveTools.value = [];
  narrative.value = "";
  liveSummary.value = "";
  liveRiskScore.value = 0;
  stage.value = "PREPARE";
  stageMessage.value = "建立受信任的文档上下文";
  try {
    await streamAudit(document.value.id, (event, payload) => {
      if (event === "start") {
        liveReviewKey.value = String(payload.reviewId || payload.auditId || "");
      } else if (event === "stage") {
        stage.value = String(payload.stage || "PREPARE").toUpperCase();
        stageMessage.value = String(payload.message || "");
      } else if (event === "tool") {
        liveTools.value.push({
          name: String(payload.name || "unknown"),
          ok: Boolean(payload.ok),
          code: String(payload.code || ""),
          summary: String(payload.summary || ""),
        });
      } else if (event === "finding") {
        liveFindings.value.push(payload as unknown as StreamFinding);
      } else if (event === "narrative") {
        narrative.value += String(payload.text || "");
      } else if (event === "summary") {
        liveSummary.value = String(payload.text || "");
        liveRiskScore.value = Number(payload.riskScore || 0);
        stage.value = "SUMMARY";
      } else if (event === "done") {
        liveReviewKey.value = String(payload.reviewId || payload.auditId || liveReviewKey.value);
        stage.value = "DONE";
      } else if (event === "cancelled") {
        stage.value = "CANCELLED";
        stageMessage.value = "审核已取消";
      } else if (event === "error") {
        streamError.value = String(payload.message || "审核失败");
        stage.value = "FAILED";
      }
    }, streamController.signal);
    if (liveReviewKey.value) {
      await loadDetail(liveReviewKey.value);
      if (routeReviewKey.value !== liveReviewKey.value) {
        await router.replace(`/reviews/${liveReviewKey.value}`);
      }
    }
    if (!streamError.value) showToast("审核已完成，等待人工复核", "success");
  } catch (reason) {
    if (!(reason instanceof DOMException && reason.name === "AbortError")) {
      streamError.value = reason instanceof Error ? reason.message : "审核失败";
      stage.value = "FAILED";
    }
  } finally {
    streaming.value = false;
  }
}

async function cancelReview() {
  if (!streaming.value) return;
  busyAction.value = "cancel";
  try {
    if (liveReviewKey.value) await api.cancelReview(liveReviewKey.value);
    streamController?.abort();
    stage.value = "CANCELLED";
    showToast("取消请求已记录", "info");
    if (liveReviewKey.value) await loadDetail(liveReviewKey.value);
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "取消失败", "error");
  } finally {
    streaming.value = false;
    busyAction.value = "";
  }
}

async function decideFinding(finding: Finding, decision: "CONFIRM" | "FALSE_POSITIVE") {
  const comment = (reviewComments.value[finding.findingKey] || "").trim();
  if (!comment) {
    showToast("请填写人工复核意见", "error");
    return;
  }
  busyAction.value = finding.findingKey;
  try {
    await api.reviewFinding(finding.findingKey, decision, comment);
    showToast(decision === "CONFIRM" ? "风险已确认" : "已标记为误报", "success");
    await loadDetail();
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "复核失败", "error");
  } finally {
    busyAction.value = "";
  }
}

function openTask(finding: Finding) {
  taskFinding.value = finding;
  taskDescription.value = finding.suggestion || `整改：${finding.title}`;
  taskAssignee.value = assignees.value[0]?.userId || "";
  const date = new Date();
  date.setDate(date.getDate() + 7);
  taskDueDate.value = date.toISOString().slice(0, 10);
}

async function createTask() {
  if (!taskFinding.value || !taskAssignee.value || !taskDueDate.value || !taskDescription.value.trim()) {
    showToast("请完整填写负责人、截止日期和整改要求", "error");
    return;
  }
  busyAction.value = "task";
  try {
    await api.createRemediation({
      findingKey: taskFinding.value.findingKey,
      assigneeId: taskAssignee.value,
      dueDate: taskDueDate.value,
      description: taskDescription.value.trim(),
    });
    taskFinding.value = null;
    showToast("整改任务已创建", "success");
    await loadDetail();
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "整改任务创建失败", "error");
  } finally {
    busyAction.value = "";
  }
}

async function generateReport() {
  if (!review.value) return;
  busyAction.value = "report";
  try {
    const report = await api.generateReport(review.value.reviewKey);
    showToast(`报告 v${report.versionNo} 已生成`, "success");
    await loadDetail();
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "报告生成失败", "error");
  } finally {
    busyAction.value = "";
  }
}

async function download(report: ReportMetadata) {
  busyAction.value = report.reportKey;
  try {
    await api.downloadReport(report);
    showToast("报告下载已开始", "success");
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "报告下载失败", "error");
  } finally {
    busyAction.value = "";
  }
}

async function approve() {
  if (!review.value || !approvalComment.value.trim()) {
    showToast("请填写最终人工批准意见", "error");
    return;
  }
  busyAction.value = "approve";
  try {
    await api.approveReview(review.value.reviewKey, approvalComment.value.trim());
    showToast("审核已人工批准", "success");
    await loadDetail();
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "批准失败", "error");
  } finally {
    busyAction.value = "";
  }
}

async function selectFinding(finding: Finding) {
  selectedFinding.value = finding.findingKey;
  await nextTick();
  if (finding.matchStart != null) {
    documentPanel.value?.querySelector(`mark[data-start="${finding.matchStart}"]`)
      ?.scrollIntoView({ behavior: "smooth", block: "center" });
  }
}

onMounted(async () => {
  if (routeReviewKey.value) await loadDetail(routeReviewKey.value);
  else if (newDocumentId.value) await loadNewDocument();
  else loading.value = false;
});
onBeforeUnmount(() => {
  streamController?.abort();
  loadController?.abort();
});
</script>

<template>
  <div class="workspace-page review-workspace">
    <header class="page-heading">
      <div>
        <span class="eyebrow">Evidence workbench</span>
        <h1>审核工作台</h1>
        <p>{{ document?.title || "选择文档后开始审核" }}<span v-if="reviewKey" class="mono"> · {{ reviewKey }}</span></p>
      </div>
      <div class="heading-actions">
        <button class="button secondary" type="button" @click="router.push('/reviews')"><ListRestart :size="16" /> 返回台账</button>
        <button v-if="streaming" class="button danger" type="button" :disabled="busyAction === 'cancel'" @click="cancelReview">
          <CircleStop :size="16" /> 取消审核
        </button>
        <button v-else-if="newDocumentId || review?.status === 'CANCELLED' || review?.status === 'FAILED'" class="button primary" type="button" :disabled="!document" @click="startReview">
          <Play :size="16" /> {{ review ? "重新审核" : "开始审核" }}
        </button>
        <button v-else-if="reviewKey" class="icon-button" type="button" title="刷新审核详情" :disabled="loading" @click="loadDetail()">
          <RefreshCw :size="17" :class="{ rotating: loading }" />
        </button>
      </div>
    </header>

    <div v-if="loading && !document" class="page-loading" aria-busy="true"><span class="spinner"></span>读取审核快照</div>
    <div v-else-if="error" class="error-state" role="alert">
      <AlertTriangle :size="24" /><strong>审核工作台加载失败</strong><span>{{ error }}</span>
      <button class="button secondary" type="button" @click="routeReviewKey ? loadDetail() : loadNewDocument()">重试</button>
    </div>
    <div v-else-if="!document" class="empty-state">
      <FileSearch :size="34" /><strong>未选择文档</strong><span>请从文档台账进入审核工作台。</span>
      <button class="button primary" type="button" @click="router.push('/documents')">打开文档台账</button>
    </div>

    <template v-else>
      <section v-if="streaming || stage !== 'IDLE'" class="process-rail" aria-label="审核流程">
        <div v-for="[key, label] in phases" :key="key" :class="['process-step', phaseState(key)]">
          <span><Check v-if="phaseState(key) === 'done'" :size="12" /><i v-else></i></span>
          <strong>{{ label }}</strong>
        </div>
        <div class="process-message"><span v-if="streaming" class="spinner small-spinner"></span>{{ stageMessage || (stage === "DONE" ? "审核快照已写入" : stage) }}</div>
      </section>

      <div v-if="streamError" class="inline-alert error" role="alert">
        <AlertTriangle :size="17" /><span>{{ streamError }}</span>
        <button class="text-link" type="button" @click="startReview">重新尝试</button>
      </div>

      <section class="metric-strip review-metrics">
        <div><span>初始风险分</span><strong :class="{ dangerText: displayRiskScore >= 60 }">{{ displayRiskScore }}</strong></div>
        <div><span>风险项</span><strong>{{ detail?.findings.length ?? liveFindings.length }}</strong></div>
        <div><span>关键实体</span><strong>{{ detail?.entities.length ?? 0 }}</strong></div>
        <div><span>工具调用</span><strong>{{ detail?.toolExecutions.length ?? liveTools.length }}</strong></div>
        <div><span>运行状态</span><strong class="metric-status">{{ reviewStatusLabel[review?.status || ""] || (streaming ? "审核中" : "未开始") }}</strong></div>
      </section>

      <div class="review-grid">
        <section class="source-panel">
          <header class="section-head">
            <div><span class="eyebrow">Source</span><h2>原文与命中定位</h2></div>
            <span>{{ document.pageCount ? `${document.pageCount} 页` : "结构化文本" }} · {{ document.contentLength }} 字</span>
          </header>
          <div ref="documentPanel" class="document-paper" v-html="highlightedHtml"></div>
          <footer><FileText :size="14" /> {{ document.sourceFilename }} · v{{ document.versionNo }} · SHA-256 由服务端留存</footer>
        </section>

        <aside class="evidence-panel">
          <section class="summary-sheet">
            <header class="section-head"><div><span class="eyebrow">Summary</span><h2>风险汇总</h2></div><ShieldAlert :size="19" /></header>
            <p>{{ displaySummary }}</p>
            <div v-if="narrative" class="narrative"><Sparkles :size="15" /><span>{{ narrative }}</span></div>
          </section>

          <section class="tool-sheet">
            <header class="section-head"><div><span class="eyebrow">Trace</span><h2>工具执行轨迹</h2></div><span>{{ detail?.toolExecutions.length ?? liveTools.length }}</span></header>
            <div v-if="!displayTools.length" class="mini-empty">尚无工具执行记录</div>
            <ul v-else class="tool-list">
              <li v-for="tool in displayTools" :key="tool.id">
                <span :class="['tool-state', tool.ok ? 'ok' : 'bad']"></span>
                <div><strong>{{ tool.name }}</strong><small>{{ tool.summary }}</small></div>
                <time>{{ tool.tail }}</time>
              </li>
            </ul>
          </section>
        </aside>
      </div>

      <section class="result-section">
        <header class="section-head">
          <div><span class="eyebrow">Findings</span><h2>风险项与人工复核</h2></div>
          <span>{{ persistedFindings.length || liveFindings.length }} 项</span>
        </header>
        <div v-if="!persistedFindings.length && !liveFindings.length" class="empty-state compact-empty">
          <FileCheck2 :size="28" /><strong>{{ streaming ? "规则正在运行" : "未发现风险项" }}</strong>
          <span>未发现风险不等于完成法律审查，仍需人工确认。</span>
        </div>
        <div v-else-if="!persistedFindings.length" class="finding-grid">
          <article v-for="finding in liveFindings" :key="finding.id" class="finding-card">
            <div class="finding-title">
              <span :class="['severity-tag', `severity-${finding.severity}`]">{{ severityLabel[finding.severity.toUpperCase()] }}</span>
              <strong>{{ finding.rule }}</strong>
            </div>
            <p>{{ finding.description }}</p>
            <blockquote v-if="finding.matchedText">{{ finding.matchedText }}</blockquote>
          </article>
        </div>
        <div v-else class="finding-stack">
          <article
            v-for="{ finding, citations } in persistedFindings"
            :key="finding.findingKey"
            :class="['finding-record', { selected: selectedFinding === finding.findingKey }]"
          >
            <button class="finding-main" type="button" @click="selectFinding(finding)">
              <span :class="['severity-tag', severityClass(finding.severity)]">{{ severityLabel[finding.severity] || finding.severity }}</span>
              <span class="finding-copy">
                <strong>{{ finding.title }}</strong>
                <small class="mono">{{ finding.ruleCode || finding.sourceType }} · {{ finding.findingKey }}</small>
              </span>
              <span :class="['status-chip', statusClass(finding.status)]">{{ findingStatusLabel[finding.status] || finding.status }}</span>
            </button>
            <div class="finding-body">
              <p>{{ finding.description }}</p>
              <div class="evidence-quote">
                <span>原文证据</span>
                <blockquote>{{ finding.evidenceText || "全文缺失检查：未定位到对应必备表述" }}</blockquote>
                <small>{{ formatSourceLocation(finding) ? `${formatSourceLocation(finding)} · ` : "" }}置信度 {{ Math.round(finding.confidence * 100) }}%</small>
              </div>
              <p class="suggestion"><strong>整改建议：</strong>{{ finding.suggestion || "由人工复核后确定。" }}</p>
              <div v-if="citations.length" class="citation-list">
                <div v-for="citation in citations" :key="citation.regulationCode">
                  <BookOpenCheck :size="15" />
                  <span><strong>{{ citation.regulationCode }} · {{ citation.title }}</strong><small>{{ citation.versionLabel }} · 生效 {{ citation.effectiveDate }} · {{ citation.sourceName }} · DEMO</small></span>
                </div>
              </div>
              <div v-if="finding.reviewerComment" class="review-note"><strong>人工意见</strong><span>{{ finding.reviewerComment }}</span></div>
              <div v-if="finding.status === 'OPEN' && hasRole('REVIEWER')" class="decision-box">
                <textarea v-model="reviewComments[finding.findingKey]" rows="2" maxlength="2000" placeholder="填写判断依据；不能为空"></textarea>
                <div>
                  <button class="button secondary small" type="button" :disabled="busyAction === finding.findingKey" @click="decideFinding(finding, 'FALSE_POSITIVE')">标记误报</button>
                  <button class="button danger small" type="button" :disabled="busyAction === finding.findingKey" @click="decideFinding(finding, 'CONFIRM')">确认风险</button>
                </div>
              </div>
              <button
                v-if="finding.status === 'CONFIRMED' && hasRole('COMPLIANCE_ADMIN') && !detail?.remediations.some((task) => task.findingId === finding.id)"
                class="button primary small"
                type="button"
                @click="openTask(finding)"
              ><Wrench :size="14" /> 创建整改任务</button>
            </div>
          </article>
        </div>
      </section>

      <div v-if="detail" class="lower-grid">
        <section class="result-section">
          <header class="section-head"><div><span class="eyebrow">Entities</span><h2>关键实体</h2></div><span>{{ detail.entities.length }}</span></header>
          <div v-if="!detail.entities.length" class="mini-empty">未抽取到关键实体</div>
          <div v-else class="table-wrap">
            <table class="data-table compact-table">
              <thead><tr><th>类型</th><th>值</th><th>定位</th><th>置信度</th></tr></thead>
              <tbody><tr v-for="entity in detail.entities" :key="entity.entityKey">
                <td data-label="类型">{{ entityTypeLabel[entity.type] || entity.type }}</td><td data-label="值">{{ entity.value }}</td>
                <td data-label="定位">{{ formatSourceLocation({ ...entity, matchStart: entity.start, matchEnd: entity.end }) }}</td>
                <td data-label="置信度">{{ Math.round(entity.confidence * 100) }}%</td>
              </tr></tbody>
            </table>
          </div>
        </section>

        <section class="result-section">
          <header class="section-head"><div><span class="eyebrow">Reports</span><h2>报告快照</h2></div><span>{{ detail.reports.length }}</span></header>
          <div v-if="detail.reports.length" class="report-list">
            <div v-for="report in detail.reports" :key="report.reportKey">
              <FileCheck2 :size="18" /><span><strong>{{ report.fileName }}</strong><small>{{ formatBytes(report.sizeBytes) }} · {{ formatDateTime(report.createdAt) }} · SHA {{ report.sha256.slice(0, 12) }}…</small></span>
              <button class="icon-button" type="button" title="下载 DOCX" :disabled="busyAction === report.reportKey" @click="download(report)"><Download :size="16" /></button>
            </div>
          </div>
          <div v-else class="mini-empty">尚未生成报告</div>
          <button v-if="reportReady" class="button secondary report-button" type="button" :disabled="busyAction === 'report'" @click="generateReport">
            <FileText :size="15" /> {{ detail.reports.length ? "按最新快照生成新版本" : "生成 DOCX 报告" }}
          </button>
        </section>
      </div>

      <section v-if="detail?.remediations.length" class="result-section">
        <header class="section-head"><div><span class="eyebrow">Remediation</span><h2>关联整改任务</h2></div><span>{{ detail.remediations.length }}</span></header>
        <div class="task-inline-list">
          <button v-for="task in detail.remediations" :key="task.taskKey" type="button" @click="router.push('/remediations')">
            <span class="mono">{{ task.taskKey }}</span><strong>{{ task.description }}</strong>
            <span :class="['status-chip', statusClass(task.status)]">{{ remediationStatusLabel[task.status] || task.status }}</span>
          </button>
        </div>
      </section>

      <section v-if="canApprove" class="approval-band">
        <div><strong>最终人工批准</strong><span>{{ allFindingsReviewed ? "风险项已闭环，可提交批准。" : "仍有风险项未闭环，服务端会拒绝批准。" }}</span></div>
        <input v-model="approvalComment" maxlength="2000" placeholder="填写最终批准意见" />
        <button class="button primary" type="button" :disabled="busyAction === 'approve' || !allFindingsReviewed" @click="approve"><Check :size="16" /> 批准审核</button>
      </section>
      <div v-else-if="terminal" class="inline-alert success"><FileCheck2 :size="17" />当前运行已进入终态：{{ reviewStatusLabel[review?.status || ""] }}</div>
    </template>

    <div v-if="taskFinding" class="modal-backdrop" @click.self="taskFinding = null">
      <form class="modal-sheet" @submit.prevent="createTask">
        <header><div><span class="eyebrow">整改任务</span><h2>创建整改任务</h2></div><button class="icon-button" type="button" title="关闭" @click="taskFinding = null"><X :size="18" /></button></header>
        <p>{{ taskFinding.title }} · {{ taskFinding.findingKey }}</p>
        <label class="field"><span>负责人</span><select v-model="taskAssignee" required><option value="" disabled>选择同租户账户</option><option v-for="item in assignees" :key="item.userId" :value="item.userId">{{ item.userId }} · {{ item.role }}</option></select></label>
        <label class="field"><span>截止日期</span><input v-model="taskDueDate" type="date" required /></label>
        <label class="field"><span>整改要求</span><textarea v-model="taskDescription" rows="4" maxlength="2000" required></textarea></label>
        <footer><button class="button secondary" type="button" @click="taskFinding = null">取消</button><button class="button primary" type="submit" :disabled="busyAction === 'task'">创建任务</button></footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.review-workspace { max-width: 1480px; }
.review-metrics { grid-template-columns: repeat(5, 1fr); margin: 22px 0; }
.metric-status { font-size: 16px !important; }
.dangerText { color: var(--red-700) !important; }
.process-rail { display: flex; align-items: center; gap: 0; min-height: 68px; padding: 12px 18px; background: var(--ink-900); color: white; overflow-x: auto; }
.process-step { display: flex; align-items: center; gap: 7px; flex: 0 0 auto; color: #79817c; font-size: 11px; }
.process-step:not(:last-of-type)::after { content: ""; width: 32px; height: 1px; margin: 0 8px; background: #424844; }
.process-step > span { width: 19px; height: 19px; display: grid; place-items: center; border: 1px solid #59615c; border-radius: 50%; }
.process-step i { width: 5px; height: 5px; background: currentColor; border-radius: 50%; }
.process-step.active { color: white; }.process-step.active > span { border-color: var(--amber-300); color: var(--amber-300); }
.process-step.done { color: #9ed8b4; }.process-step.done > span { border-color: #5c9d75; }
.process-message { margin-left: auto; display: flex; align-items: center; gap: 8px; padding-left: 20px; color: #c9cfcb; font-size: 10px; white-space: nowrap; }
.review-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(340px, .65fr); gap: 18px; align-items: stretch; }
.source-panel, .summary-sheet, .tool-sheet, .result-section { background: var(--paper); border: 1px solid var(--line-strong); }
.source-panel { min-height: 600px; display: flex; flex-direction: column; }
.section-head { min-height: 62px; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 12px 16px; border-bottom: 1px solid var(--line); }
.section-head h2 { margin: 2px 0 0; font-family: var(--font-display); font-size: 17px; }
.section-head > span { color: var(--ink-500); font-size: 10px; }
.document-paper { flex: 1; max-height: 720px; overflow: auto; padding: 38px clamp(22px, 5vw, 64px); white-space: pre-wrap; overflow-wrap: anywhere; background: #fffefb; color: var(--ink-900); font-family: var(--font-document); font-size: 14px; line-height: 2; }
.source-panel footer { display: flex; align-items: center; gap: 7px; padding: 10px 16px; color: var(--ink-500); background: var(--canvas); border-top: 1px solid var(--line); font-size: 10px; }
.evidence-panel { display: grid; grid-template-rows: auto minmax(280px, 1fr); gap: 18px; }
.summary-sheet > p { margin: 0; padding: 18px; font-family: var(--font-document); font-size: 14px; line-height: 1.8; }
.narrative { display: flex; align-items: flex-start; gap: 8px; margin: 0 16px 16px; padding: 11px; color: var(--green-800); background: var(--green-50); border-left: 3px solid var(--green-600); font-size: 11px; line-height: 1.65; }
.tool-list { max-height: 430px; margin: 0; padding: 8px 14px 14px; overflow-y: auto; list-style: none; }
.tool-list li { display: grid; grid-template-columns: 10px 1fr auto; align-items: start; gap: 9px; padding: 10px 2px; border-bottom: 1px solid var(--line); }
.tool-list li:last-child { border-bottom: 0; }.tool-list div { display: grid; gap: 3px; min-width: 0; }
.tool-list strong { font-family: var(--font-mono); font-size: 10px; }.tool-list small { color: var(--ink-500); font-size: 10px; line-height: 1.5; }
.tool-list time { color: var(--ink-400); font-family: var(--font-mono); font-size: 9px; white-space: nowrap; }
.tool-state { width: 7px; height: 7px; margin-top: 4px; border-radius: 50%; }.tool-state.ok { background: var(--green-600); }.tool-state.bad { background: var(--red-600); }
.result-section { margin-top: 18px; }
.finding-stack { display: grid; }
.finding-record { border-bottom: 1px solid var(--line); }.finding-record:last-child { border-bottom: 0; }.finding-record.selected { box-shadow: inset 3px 0 var(--red-600); }
.finding-main { width: 100%; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 14px 16px; color: inherit; background: transparent; border: 0; text-align: left; }
.finding-main:hover { background: var(--canvas); }.finding-copy { display: grid; gap: 3px; }.finding-copy small { color: var(--ink-500); font-size: 9px; }
.finding-body { padding: 0 16px 18px 58px; }.finding-body > p { margin: 0 0 12px; line-height: 1.7; }
.evidence-quote { padding: 12px 14px; background: var(--canvas); border-left: 3px solid var(--amber-500); }.evidence-quote > span { color: var(--ink-500); font-size: 9px; font-weight: 800; text-transform: uppercase; letter-spacing: 0; }
.evidence-quote blockquote { margin: 7px 0; font-family: var(--font-document); line-height: 1.7; }.evidence-quote small { color: var(--ink-500); font-size: 9px; }
.suggestion { margin-top: 13px !important; color: var(--ink-700); font-size: 12px; }
.citation-list { display: grid; gap: 6px; margin: 12px 0; }.citation-list > div { display: flex; align-items: flex-start; gap: 8px; padding: 8px 10px; border: 1px solid var(--line); }
.citation-list svg { margin-top: 2px; color: var(--green-700); }.citation-list span { display: grid; gap: 2px; }.citation-list strong { font-size: 10px; }.citation-list small { color: var(--ink-500); font-size: 9px; }
.review-note { display: grid; grid-template-columns: 90px 1fr; gap: 9px; padding: 10px; color: var(--green-800); background: var(--green-50); font-size: 11px; }
.decision-box { display: grid; gap: 8px; margin-top: 12px; padding: 12px; border: 1px solid var(--line); background: var(--canvas); }
.decision-box textarea { width: 100%; }.decision-box div { display: flex; justify-content: flex-end; gap: 8px; }
.finding-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; padding: 14px; }.finding-card { padding: 14px; border: 1px solid var(--line); }.finding-title { display: flex; align-items: center; gap: 8px; }.finding-card blockquote { margin: 10px 0 0; color: var(--ink-600); }
.lower-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.lower-grid > .result-section { min-width: 0; }
.compact-table { min-width: 600px; }
.report-list { display: grid; }.report-list > div { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 10px; padding: 11px 14px; border-bottom: 1px solid var(--line); }
.report-list span { display: grid; gap: 3px; }.report-list small { color: var(--ink-500); font-size: 9px; }.report-button { margin: 12px 14px; }
.task-inline-list { display: grid; }.task-inline-list button { display: grid; grid-template-columns: 180px 1fr auto; gap: 14px; align-items: center; padding: 12px 16px; color: inherit; background: transparent; border: 0; border-bottom: 1px solid var(--line); text-align: left; }.task-inline-list button:hover { background: var(--canvas); }
.approval-band { display: grid; grid-template-columns: minmax(220px, .65fr) minmax(260px, 1fr) auto; gap: 14px; align-items: center; margin-top: 18px; padding: 16px; color: white; background: var(--ink-900); }.approval-band div { display: grid; gap: 3px; }.approval-band span { color: #adb5b0; font-size: 10px; }.approval-band input { height: 40px; }

@media (max-width: 1050px) {
  .review-grid, .lower-grid { grid-template-columns: 1fr; }.source-panel { min-height: 500px; }.review-metrics { grid-template-columns: repeat(3, 1fr); }.approval-band { grid-template-columns: 1fr; }
}
@media (max-width: 680px) {
  .review-metrics { grid-template-columns: repeat(2, 1fr); }.process-message { display: none; }.process-step:not(:last-of-type)::after { width: 14px; margin: 0 4px; }.process-step strong { display: none; }
  .document-paper { padding: 24px 18px; }.compact-table { min-width: 0; }.finding-body { padding-left: 16px; }.finding-main { grid-template-columns: auto 1fr; }.finding-main > .status-chip { grid-column: 2; justify-self: start; }
  .task-inline-list button { grid-template-columns: 1fr; }.review-note { grid-template-columns: 1fr; }
}
</style>
