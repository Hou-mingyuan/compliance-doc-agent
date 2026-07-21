<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  AlertTriangle,
  CalendarClock,
  CheckCircle2,
  ClipboardCheck,
  FileUp,
  RefreshCw,
  RotateCcw,
  Search,
  X,
} from "@lucide/vue";
import { api, type EvidenceRecord, type RemediationTask } from "../api";
import { hasRole, useAuth } from "../auth";
import { showToast } from "../toast";
import { formatDateTime, remediationStatusLabel, statusClass } from "../ui";

const { state: authState } = useAuth();
const tasks = ref<RemediationTask[]>([]);
const selected = ref<RemediationTask | null>(null);
const evidence = ref<EvidenceRecord[]>([]);
const loading = ref(true);
const detailLoading = ref(false);
const error = ref("");
const query = ref("");
const status = ref("ALL");
const evidenceText = ref("");
const reviewComment = ref("");
const reopenReason = ref("");
const busy = ref("");
let controller: AbortController | null = null;

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  return tasks.value.filter((task) => {
    const matchesStatus = status.value === "ALL" || task.status === status.value;
    const matchesText = !keyword
      || task.taskKey.toLowerCase().includes(keyword)
      || task.description.toLowerCase().includes(keyword)
      || task.assigneeId.toLowerCase().includes(keyword);
    return matchesStatus && matchesText;
  });
});
const overdue = computed(() => tasks.value.filter((task) =>
  !["CLOSED", "VERIFIED"].includes(task.status) && task.dueDate < new Date().toISOString().slice(0, 10)).length);
const waitingReview = computed(() => tasks.value.filter((task) => task.status === "EVIDENCE_SUBMITTED").length);
const closed = computed(() => tasks.value.filter((task) => task.status === "CLOSED").length);
const assignedToMe = computed(() => selected.value?.assigneeId === authState.user?.userId);

async function load() {
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    tasks.value = await api.listRemediations(undefined, controller.signal);
    if (selected.value) {
      const current = tasks.value.find((task) => task.taskKey === selected.value?.taskKey);
      if (current) selected.value = current;
    }
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === "AbortError") return;
    error.value = reason instanceof Error ? reason.message : "整改任务加载失败";
  } finally {
    loading.value = false;
  }
}

async function openTask(task: RemediationTask) {
  detailLoading.value = true;
  selected.value = task;
  evidence.value = [];
  evidenceText.value = "";
  reviewComment.value = "";
  reopenReason.value = "";
  try {
    const detail = await api.getRemediation(task.taskKey);
    selected.value = detail.task;
    evidence.value = detail.evidence;
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "任务详情加载失败", "error");
    selected.value = null;
  } finally {
    detailLoading.value = false;
  }
}

async function mutate(name: string, action: () => Promise<RemediationTask>, success: string) {
  if (!selected.value) return;
  busy.value = name;
  try {
    selected.value = await action();
    showToast(success, "success");
    await load();
    await openTask(selected.value);
  } catch (reason) {
    showToast(reason instanceof Error ? reason.message : "操作失败", "error");
  } finally {
    busy.value = "";
  }
}

function start() {
  if (!selected.value) return;
  void mutate("start", () => api.startRemediation(selected.value!.taskKey), "整改任务已开始");
}
function submitEvidence() {
  if (!selected.value || !evidenceText.value.trim()) {
    showToast("请填写脱敏整改证据", "error");
    return;
  }
  void mutate("evidence", () => api.submitEvidence(selected.value!.taskKey, evidenceText.value.trim()), "整改证据已提交");
}
function review(approved: boolean) {
  if (!selected.value || !reviewComment.value.trim()) {
    showToast("请填写复审意见", "error");
    return;
  }
  void mutate("review", () => api.reviewEvidence(selected.value!.taskKey, approved, reviewComment.value.trim()),
    approved ? "整改证据已验证" : "整改证据已退回");
}
function close() {
  if (!selected.value) return;
  void mutate("close", () => api.closeRemediation(selected.value!.taskKey), "整改任务已关闭");
}
function reopen() {
  if (!selected.value || !reopenReason.value.trim()) {
    showToast("请填写重开原因", "error");
    return;
  }
  void mutate("reopen", () => api.reopenRemediation(selected.value!.taskKey, reopenReason.value.trim()), "整改任务已重开");
}

onMounted(load);
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <div class="workspace-page">
    <header class="page-heading">
      <div><span class="eyebrow">Remediation ledger</span><h1>整改任务</h1><p>从风险确认到证据提交、复审、关闭与重开的完整状态链。</p></div>
      <button class="button secondary" type="button" :disabled="loading" @click="load"><RefreshCw :size="16" :class="{ rotating: loading }" /> 刷新</button>
    </header>

    <section class="metric-strip">
      <div><span>可见任务</span><strong>{{ tasks.length }}</strong></div>
      <div><span>等待复审</span><strong>{{ waitingReview }}</strong></div>
      <div><span>已逾期</span><strong :class="{ dangerText: overdue }">{{ overdue }}</strong></div>
      <div><span>已关闭</span><strong>{{ closed }}</strong></div>
    </section>

    <section class="register-section">
      <div class="register-toolbar">
        <div class="search-control"><Search :size="16" /><input v-model="query" type="search" placeholder="搜索任务、负责人或整改要求" aria-label="搜索整改任务" /></div>
        <select v-model="status" class="filter-select" aria-label="按整改状态筛选">
          <option value="ALL">全部状态</option><option v-for="(label, value) in remediationStatusLabel" :key="value" :value="value">{{ label }}</option>
        </select>
        <span class="result-count">{{ filtered.length }} 条</span>
      </div>
      <div v-if="loading" class="table-state"><span class="spinner"></span>读取整改任务</div>
      <div v-else-if="error" class="error-state" role="alert"><AlertTriangle :size="23" /><strong>整改任务加载失败</strong><span>{{ error }}</span><button class="button secondary" type="button" @click="load">重试</button></div>
      <div v-else-if="!filtered.length" class="empty-state"><ClipboardCheck :size="34" /><strong>{{ tasks.length ? "没有符合筛选条件的任务" : "当前身份没有可见整改任务" }}</strong><span>整改任务需由合规管理员从已确认风险创建。</span></div>
      <div v-else class="table-wrap">
        <table class="data-table">
          <thead><tr><th>任务</th><th>整改要求</th><th>负责人</th><th>截止日期</th><th>状态</th><th>版本</th><th class="action-col">操作</th></tr></thead>
          <tbody><tr v-for="task in filtered" :key="task.taskKey">
            <td data-label="任务"><strong class="mono">{{ task.taskKey }}</strong><small>风险 #{{ task.findingId }}</small></td>
            <td data-label="整改要求" class="description-cell">{{ task.description }}</td>
            <td data-label="负责人">{{ task.assigneeId }}</td>
            <td data-label="截止日期"><span :class="{ overdue: task.dueDate < new Date().toISOString().slice(0, 10) && task.status !== 'CLOSED' }">{{ task.dueDate }}</span></td>
            <td data-label="状态"><span :class="['status-chip', statusClass(task.status)]">{{ remediationStatusLabel[task.status] || task.status }}</span></td>
            <td data-label="版本">v{{ task.versionNo }}</td>
            <td data-label="操作" class="row-actions"><button class="button secondary small" type="button" @click="openTask(task)">查看与处理</button></td>
          </tr></tbody>
        </table>
      </div>
    </section>

    <aside v-if="selected" class="drawer-backdrop" @click.self="selected = null">
      <section class="task-drawer">
        <header>
          <div><span class="eyebrow">Task detail</span><h2>{{ selected.taskKey }}</h2></div>
          <button class="icon-button" type="button" title="关闭" @click="selected = null"><X :size="18" /></button>
        </header>
        <div v-if="detailLoading" class="table-state"><span class="spinner"></span>读取任务详情</div>
        <template v-else>
          <div class="task-summary">
            <span :class="['status-chip', statusClass(selected.status)]">{{ remediationStatusLabel[selected.status] }}</span>
            <dl>
              <div><dt>负责人</dt><dd>{{ selected.assigneeId }}</dd></div>
              <div><dt>截止日期</dt><dd>{{ selected.dueDate }}</dd></div>
              <div><dt>创建人</dt><dd>{{ selected.createdBy }}</dd></div>
              <div><dt>版本</dt><dd>v{{ selected.versionNo }}</dd></div>
            </dl>
            <h3>整改要求</h3><p>{{ selected.description }}</p>
            <div v-if="selected.reviewComment" class="review-note"><strong>最近复审意见</strong><span>{{ selected.reviewComment }}</span></div>
          </div>

          <section class="evidence-ledger">
            <div class="subheading"><h3>证据记录</h3><span>{{ evidence.length }} 条</span></div>
            <div v-if="!evidence.length" class="mini-empty">尚未提交整改证据</div>
            <article v-for="item in evidence" :key="item.id">
              <FileUp :size="16" /><div><strong>{{ item.submittedBy }}</strong><p>{{ item.evidenceText }}</p><time>{{ formatDateTime(item.submittedAt) }}</time></div>
            </article>
          </section>

          <section class="task-action-zone">
            <button v-if="assignedToMe && ['OPEN','REJECTED','REOPENED'].includes(selected.status)" class="button primary" type="button" :disabled="busy === 'start'" @click="start">
              <CalendarClock :size="16" /> 开始整改
            </button>
            <form v-if="assignedToMe && selected.status === 'IN_PROGRESS'" @submit.prevent="submitEvidence">
              <label class="field"><span>脱敏整改证据</span><textarea v-model="evidenceText" rows="4" maxlength="4000" placeholder="说明修订内容、版本和可复核依据；不要粘贴敏感原文" required></textarea></label>
              <button class="button primary" type="submit" :disabled="busy === 'evidence'"><FileUp :size="16" /> 提交证据</button>
            </form>
            <form v-if="hasRole('REVIEWER') && selected.status === 'EVIDENCE_SUBMITTED'" @submit.prevent>
              <label class="field"><span>复审意见</span><textarea v-model="reviewComment" rows="3" maxlength="2000" placeholder="写明接受或退回依据" required></textarea></label>
              <div class="split-actions"><button class="button secondary" type="button" :disabled="busy === 'review'" @click="review(false)">退回补充</button><button class="button primary" type="button" :disabled="busy === 'review'" @click="review(true)"><CheckCircle2 :size="16" /> 验证通过</button></div>
            </form>
            <button v-if="hasRole('COMPLIANCE_ADMIN') && selected.status === 'VERIFIED'" class="button primary" type="button" :disabled="busy === 'close'" @click="close"><ClipboardCheck :size="16" /> 关闭任务</button>
            <form v-if="hasRole('COMPLIANCE_ADMIN') && selected.status === 'CLOSED'" @submit.prevent="reopen">
              <label class="field"><span>重开原因</span><textarea v-model="reopenReason" rows="3" maxlength="1000" required></textarea></label>
              <button class="button secondary" type="submit" :disabled="busy === 'reopen'"><RotateCcw :size="16" /> 重开任务</button>
            </form>
            <div v-if="!assignedToMe && !hasRole('REVIEWER')" class="inline-alert info">当前用户仅能查看分配给自己的任务并执行整改。</div>
          </section>
        </template>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.dangerText, .overdue { color: var(--red-700); }.description-cell { max-width: 360px; line-height: 1.5; }.data-table td small { display: block; color: var(--ink-500); font-size: 9px; }
.task-drawer { width: min(680px, 95vw); height: 100%; overflow-y: auto; background: var(--paper); border-left: 1px solid var(--line-strong); box-shadow: var(--shadow-lg); }
.task-drawer > header { position: sticky; z-index: 2; top: 0; min-height: 72px; display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; background: var(--paper); border-bottom: 1px solid var(--line); }
.task-drawer h2 { margin: 3px 0 0; font-family: var(--font-mono); font-size: 16px; }.task-summary { padding: 18px 20px; border-bottom: 1px solid var(--line); }
.task-summary dl { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 18px 0; }.task-summary dl div { display: grid; gap: 2px; }.task-summary dt { color: var(--ink-500); font-size: 9px; text-transform: uppercase; }.task-summary dd { margin: 0; font-size: 12px; }.task-summary h3 { margin: 20px 0 6px; font-size: 12px; }.task-summary p { margin: 0; line-height: 1.7; }
.evidence-ledger { padding: 18px 20px; border-bottom: 1px solid var(--line); }.subheading { display: flex; align-items: center; justify-content: space-between; }.subheading h3 { margin: 0; font-size: 13px; }.subheading span { color: var(--ink-500); font-size: 10px; }
.evidence-ledger article { display: flex; gap: 10px; padding: 13px 0; border-bottom: 1px solid var(--line); }.evidence-ledger article:last-child { border-bottom: 0; }.evidence-ledger article svg { margin-top: 3px; color: var(--green-700); }.evidence-ledger article div { min-width: 0; }.evidence-ledger p { margin: 4px 0; white-space: pre-wrap; line-height: 1.65; }.evidence-ledger time { color: var(--ink-500); font-size: 9px; }
.task-action-zone { display: grid; gap: 14px; padding: 18px 20px 36px; }.task-action-zone form { display: grid; gap: 12px; }.split-actions { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 560px) { .task-summary dl { grid-template-columns: 1fr; }.split-actions { flex-direction: column; }.split-actions .button { width: 100%; } }
</style>
