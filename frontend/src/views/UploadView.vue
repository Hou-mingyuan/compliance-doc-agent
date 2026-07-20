<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  CircleCheck,
  FilePlus2,
  FileText,
  GitBranch,
  Play,
  RefreshCw,
  Search,
  UploadCloud,
  X,
} from "@lucide/vue";
import { api, type DocumentContent, type DocumentItem, type ReviewRecord } from "../api";
import { showToast } from "../toast";

const router = useRouter();
const documents = ref<DocumentItem[]>([]);
const reviews = ref<ReviewRecord[]>([]);
const loading = ref(true);
const error = ref("");
const dragging = ref(false);
const uploading = ref(false);
const uploadLabel = ref("");
const docType = ref("CONTRACT");
const search = ref("");
const statusFilter = ref("ALL");
const page = ref(1);
const pageSize = 8;
const fileInput = ref<HTMLInputElement | null>(null);
const versionInput = ref<HTMLInputElement | null>(null);
const versionTarget = ref<DocumentItem | null>(null);
const preview = ref<DocumentContent | null>(null);
const previewBusy = ref(false);
let loadController: AbortController | null = null;
let uploadController: AbortController | null = null;

const allowed = ["pdf", "docx", "txt", "md", "markdown"];
const accept = ".pdf,.docx,.txt,.md,.markdown";
const statusLabel: Record<string, string> = {
  uploaded: "已解析",
  auditing: "审核中",
  pending_review: "待复核",
  remediation: "整改中",
  done: "已批准",
  cancelled: "已取消",
  failed: "失败",
};
const typeLabel: Record<string, string> = {
  CONTRACT: "合同",
  POLICY: "内部制度",
  PRIVACY: "隐私条款",
  DISCLOSURE: "披露材料",
  GENERAL: "通用文档",
};

const filtered = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  return documents.value.filter((item) => {
    const matchesText = !keyword || item.filename.toLowerCase().includes(keyword)
      || item.docType.toLowerCase().includes(keyword);
    return matchesText && (statusFilter.value === "ALL" || item.status === statusFilter.value);
  });
});
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)));
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize));
const activeCount = computed(() => documents.value.filter((item) => ["auditing", "remediation"].includes(item.status)).length);
const reviewedCount = computed(() => documents.value.filter((item) => ["pending_review", "done"].includes(item.status)).length);
const latestReviewByDocument = computed(() => {
  const latest = new Map<number, ReviewRecord>();
  for (const review of reviews.value) {
    const current = latest.get(review.documentId);
    if (!current || Date.parse(review.updatedAt) > Date.parse(current.updatedAt)) {
      latest.set(review.documentId, review);
    }
  }
  return latest;
});

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}
function formatDate(value: string) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

async function load() {
  loadController?.abort();
  loadController = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    const [nextDocuments, nextReviews] = await Promise.all([
      api.listDocuments(loadController.signal),
      api.listReviews(loadController.signal),
    ]);
    documents.value = nextDocuments;
    reviews.value = nextReviews;
    if (page.value > pageCount.value) page.value = pageCount.value;
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === "AbortError") return;
    error.value = reason instanceof Error ? reason.message : "文档列表加载失败";
  } finally {
    loading.value = false;
  }
}

function validateFile(file: File): string | null {
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  if (!allowed.includes(extension)) return `${file.name}：仅支持 PDF、DOCX、TXT、MD`;
  if (file.size > 5 * 1024 * 1024) return `${file.name}：文件不能超过 5MB`;
  if (file.size === 0) return `${file.name}：文件内容为空`;
  return null;
}

async function uploadFiles(files: FileList | File[]) {
  const selected = Array.from(files);
  if (!selected.length || uploading.value) return;
  const invalid = selected.map(validateFile).find(Boolean);
  if (invalid) {
    showToast(invalid, "error");
    return;
  }
  uploadController = new AbortController();
  uploading.value = true;
  let uploaded = 0;
  let duplicate = 0;
  try {
    for (const [index, file] of selected.entries()) {
      uploadLabel.value = `${index + 1}/${selected.length} · ${file.name}`;
      const result = await api.uploadDocument(file, docType.value, uploadController.signal);
      if (result.duplicate) duplicate++;
      else uploaded++;
    }
    await load();
    showToast(`上传完成：新增 ${uploaded}，重复 ${duplicate}`, "success");
  } catch (reason) {
    if (reason instanceof DOMException && reason.name === "AbortError") {
      showToast("上传已取消，已完成的文件仍保留", "info");
    } else {
      showToast(reason instanceof Error ? reason.message : "上传失败", "error");
    }
  } finally {
    uploading.value = false;
    uploadLabel.value = "";
    uploadController = null;
    if (fileInput.value) fileInput.value.value = "";
  }
}

function cancelUpload() { uploadController?.abort(); }
function openPicker() { if (!uploading.value) fileInput.value?.click(); }
function onDrop(event: DragEvent) {
  dragging.value = false;
  if (event.dataTransfer?.files) void uploadFiles(event.dataTransfer.files);
}

function chooseVersion(item: DocumentItem) {
  versionTarget.value = item;
  versionInput.value?.click();
}
async function uploadVersion(files: FileList | null) {
  const file = files?.[0];
  if (!file || !versionTarget.value) return;
  const invalid = validateFile(file);
  if (invalid) { showToast(invalid); return; }
  uploading.value = true;
  uploadLabel.value = `创建 ${versionTarget.value.filename} 的新版本`;
  uploadController = new AbortController();
  try {
    const result = await api.createVersion(versionTarget.value.id, file, uploadController.signal);
    await load();
    showToast(result.duplicate ? "该版本已存在，已返回原记录" : `版本 v${result.versionNo} 已解析`, "success");
  } catch (reason) {
    if (!(reason instanceof DOMException && reason.name === "AbortError")) {
      showToast(reason instanceof Error ? reason.message : "版本上传失败");
    }
  } finally {
    uploading.value = false;
    uploadLabel.value = "";
    uploadController = null;
    versionTarget.value = null;
    if (versionInput.value) versionInput.value.value = "";
  }
}

async function showPreview(item: DocumentItem) {
  previewBusy.value = true;
  preview.value = null;
  try { preview.value = await api.getDocument(item.id); }
  catch (reason) { showToast(reason instanceof Error ? reason.message : "正文加载失败"); }
  finally { previewBusy.value = false; }
}

function existingReview(item: DocumentItem): ReviewRecord | null {
  const review = latestReviewByDocument.value.get(Number(item.id));
  return review && !["CANCELLED", "FAILED"].includes(review.status) ? review : null;
}

function startReview(item: DocumentItem) {
  const review = existingReview(item);
  router.push(review ? `/reviews/${review.reviewKey}` : `/reviews/new/${item.id}`);
}

onMounted(load);
onBeforeUnmount(() => { loadController?.abort(); uploadController?.abort(); });
</script>

<template>
  <div class="workspace-page">
    <header class="page-heading">
      <div>
        <span class="eyebrow">Document register</span>
        <h1>文档台账</h1>
        <p>上传、解析、版本管理与审核入口。</p>
      </div>
      <button class="button secondary" type="button" :disabled="loading" title="刷新文档台账" @click="load">
        <RefreshCw :size="16" :class="{ rotating: loading }" /> 刷新
      </button>
    </header>

    <section class="metric-strip" aria-label="文档统计">
      <div><span>文档总数</span><strong>{{ documents.length }}</strong></div>
      <div><span>正在处理</span><strong>{{ activeCount }}</strong></div>
      <div><span>待复核 / 已批准</span><strong>{{ reviewedCount }}</strong></div>
      <div><span>单文件上限</span><strong>5 MB</strong></div>
    </section>

    <section class="upload-band" :class="{ dragging, busy: uploading }">
      <input ref="fileInput" type="file" :accept="accept" multiple hidden @change="uploadFiles(($event.target as HTMLInputElement).files || [])" />
      <input ref="versionInput" type="file" :accept="accept" hidden @change="uploadVersion(($event.target as HTMLInputElement).files)" />
      <div class="upload-copy">
        <UploadCloud :size="26" stroke-width="1.6" />
        <div>
          <strong>{{ uploading ? "正在写入文档台账" : "拖入文件，或从本机选择" }}</strong>
          <span>{{ uploading ? uploadLabel : "PDF · DOCX · TXT · MD；服务端校验签名与压缩结构" }}</span>
        </div>
      </div>
      <div class="upload-controls">
        <label class="field compact">
          <span>文档类型</span>
          <select v-model="docType" :disabled="uploading">
            <option v-for="(label, key) in typeLabel" :key="key" :value="key">{{ label }}</option>
          </select>
        </label>
        <button v-if="uploading" class="button danger-quiet" type="button" @click="cancelUpload"><X :size="16" /> 取消</button>
        <button v-else class="button primary" type="button" @click="openPicker"><FilePlus2 :size="16" /> 选择文件</button>
      </div>
      <div
        class="drop-capture"
        aria-hidden="true"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="onDrop"
      ></div>
    </section>

    <section class="register-section">
      <div class="register-toolbar">
        <div class="search-control">
          <Search :size="16" aria-hidden="true" />
          <input v-model="search" type="search" placeholder="搜索文件名或类型" aria-label="搜索文档" @input="page = 1" />
        </div>
        <select v-model="statusFilter" class="filter-select" aria-label="按状态筛选" @change="page = 1">
          <option value="ALL">全部状态</option>
          <option v-for="(label, value) in statusLabel" :key="value" :value="value">{{ label }}</option>
        </select>
        <span class="result-count">{{ filtered.length }} 条</span>
      </div>

      <div v-if="loading" class="table-state" aria-busy="true">
        <span class="spinner"></span><span>读取台账</span>
      </div>
      <div v-else-if="error" class="error-state" role="alert">
        <strong>文档台账加载失败</strong><span>{{ error }}</span>
        <button class="button secondary" type="button" @click="load">重试</button>
      </div>
      <div v-else-if="!filtered.length" class="empty-state compact-empty">
        <FileText :size="30" />
        <strong>{{ documents.length ? "没有符合筛选条件的文档" : "文档台账为空" }}</strong>
        <span>{{ documents.length ? "清除搜索或切换状态。" : "上传脱敏样例后即可发起审核。" }}</span>
      </div>
      <div v-else class="table-wrap">
        <table class="data-table document-table">
          <thead><tr><th>文档</th><th>类型 / 版本</th><th>状态</th><th>规模</th><th>时间</th><th class="action-col">操作</th></tr></thead>
          <tbody>
            <tr v-for="item in paged" :key="item.id">
              <td data-label="文档">
                <button class="text-link file-link" type="button" @click="showPreview(item)">
                  <FileText :size="17" /> <span>{{ item.filename }}</span>
                </button>
                <small class="mono">DOC-{{ item.id.padStart(5, "0") }} · {{ item.tenantId }}</small>
              </td>
              <td data-label="类型 / 版本"><strong>{{ typeLabel[item.docType] || item.docType }}</strong><small>v{{ item.versionNo }} · {{ item.contentType.toUpperCase() }}</small></td>
              <td data-label="状态"><span :class="['status-chip', `status-${item.status}`]">{{ statusLabel[item.status] || item.status }}</span></td>
              <td data-label="规模"><span>{{ formatBytes(item.size) }}</span><small>{{ item.pageCount ? `${item.pageCount} 页` : "结构化文本" }}</small></td>
              <td data-label="时间"><span>{{ formatDate(item.uploadedAt) }}</span></td>
              <td data-label="操作" class="row-actions">
                <button class="icon-button" type="button" title="创建文档新版本" :disabled="uploading" @click="chooseVersion(item)"><GitBranch :size="16" /><span class="sr-only">创建新版本</span></button>
                <button class="button primary small" type="button" @click="startReview(item)"><Play :size="14" /> {{ existingReview(item) ? "打开审核" : "发起审核" }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer v-if="filtered.length > pageSize" class="pagination">
        <button type="button" :disabled="page === 1" @click="page--">上一页</button>
        <span>{{ page }} / {{ pageCount }}</span>
        <button type="button" :disabled="page === pageCount" @click="page++">下一页</button>
      </footer>
    </section>

    <aside v-if="previewBusy || preview" class="drawer-backdrop" @click.self="preview = null">
      <section class="preview-drawer" aria-label="文档正文预览">
        <header>
          <div><span class="eyebrow">Source document</span><h2>{{ preview?.title || "读取正文" }}</h2></div>
          <button class="icon-button" type="button" title="关闭预览" @click="preview = null"><X :size="18" /></button>
        </header>
        <div v-if="previewBusy" class="table-state"><span class="spinner"></span>读取正文</div>
        <template v-else-if="preview">
          <div class="preview-meta"><span>{{ typeLabel[preview.docType] }}</span><span>v{{ preview.versionNo }}</span><span>{{ preview.contentLength }} 字</span><span v-if="preview.pageCount">{{ preview.pageCount }} 页</span></div>
          <pre>{{ preview.content }}</pre>
          <div class="integrity-note"><CircleCheck :size="16" /> 正文来自服务端租户校验后的解析结果</div>
        </template>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.upload-band { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 24px; min-height: 104px; margin: 22px 0 28px; padding: 20px 22px; background: var(--paper); border: 1px dashed var(--ink-400); transition: border-color .16s, background .16s; }
.upload-band.dragging { border-color: var(--green-700); background: var(--green-50); }
.upload-band.busy { border-style: solid; border-color: var(--amber-300); }
.drop-capture { position: absolute; inset: 0; z-index: 0; }
.upload-copy, .upload-controls { position: relative; z-index: 1; display: flex; align-items: center; }
.upload-copy { gap: 14px; min-width: 0; }
.upload-copy > svg { flex: 0 0 auto; color: var(--green-700); }
.upload-copy div { display: grid; gap: 3px; min-width: 0; }
.upload-copy strong { font-family: var(--font-display); font-size: 16px; }
.upload-copy span { color: var(--ink-500); font-size: 11px; }
.upload-controls { gap: 10px; flex: 0 0 auto; }
.field.compact { min-width: 160px; }
.register-section { background: var(--paper); border: 1px solid var(--line-strong); }
.register-toolbar { min-height: 60px; display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--line); }
.search-control { width: min(340px, 100%); display: flex; align-items: center; gap: 8px; padding: 0 10px; background: var(--canvas); border: 1px solid var(--line); border-radius: 5px; }
.search-control:focus-within { border-color: var(--green-700); box-shadow: 0 0 0 3px var(--green-100); }
.search-control input { width: 100%; height: 36px; padding: 0; border: 0; outline: 0; background: transparent; }
.filter-select { height: 38px; padding: 0 30px 0 10px; border: 1px solid var(--line); border-radius: 5px; background: var(--paper); }
.result-count { margin-left: auto; color: var(--ink-500); font-family: var(--font-mono); font-size: 10px; }
.file-link { max-width: 330px; display: inline-flex; align-items: center; gap: 8px; font-weight: 700; text-align: left; }
.file-link span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.document-table td > small, .document-table td > strong + small { display: block; margin-top: 4px; color: var(--ink-500); font-size: 10px; }
.row-actions { display: flex; align-items: center; justify-content: flex-end; gap: 7px; }
.pagination { display: flex; align-items: center; justify-content: flex-end; gap: 12px; padding: 11px 14px; border-top: 1px solid var(--line); color: var(--ink-500); font-size: 11px; }
.pagination button { padding: 5px 9px; border: 1px solid var(--line); border-radius: 4px; background: var(--paper); }
.pagination button:disabled { opacity: .4; }
.drawer-backdrop { position: fixed; z-index: 70; inset: 0; display: flex; justify-content: flex-end; background: rgba(19, 22, 20, .34); }
.preview-drawer { width: min(680px, 94vw); height: 100%; display: flex; flex-direction: column; background: var(--paper); border-left: 1px solid var(--line-strong); box-shadow: var(--shadow-lg); animation: drawer-in .2s ease-out; }
.preview-drawer header { min-height: 74px; display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 15px 20px; border-bottom: 1px solid var(--line); }
.preview-drawer h2 { margin: 3px 0 0; font-family: var(--font-display); font-size: 20px; }
.preview-meta { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 20px; border-bottom: 1px solid var(--line); }
.preview-meta span { padding: 3px 7px; background: var(--canvas); border: 1px solid var(--line); border-radius: 3px; color: var(--ink-600); font-size: 10px; }
.preview-drawer pre { flex: 1; margin: 0; padding: 24px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--ink-800); background: #fdfdfb; font-family: var(--font-document); font-size: 14px; line-height: 1.9; }
.integrity-note { display: flex; align-items: center; gap: 7px; padding: 11px 20px; color: var(--green-800); background: var(--green-50); border-top: 1px solid var(--green-200); font-size: 11px; }
@keyframes drawer-in { from { transform: translateX(30px); opacity: .4; } }

@media (max-width: 760px) {
  .upload-band { align-items: stretch; flex-direction: column; gap: 16px; }
  .upload-controls { justify-content: space-between; }
  .register-toolbar { flex-wrap: wrap; }
  .search-control { width: 100%; }
  .result-count { margin-left: 0; }
}
</style>
