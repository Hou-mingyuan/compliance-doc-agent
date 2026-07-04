<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { api, type DocumentItem } from "../api";

const router = useRouter();
const documents = ref<DocumentItem[]>([]);
const dragging = ref(false);
const busy = ref(false);
const loading = ref(true);
const error = ref("");
const fileInput = ref<HTMLInputElement | null>(null);

const accept = ".pdf,.doc,.docx,.txt,.md,.xlsx,.xls";

const statusLabel: Record<DocumentItem["status"], string> = {
  uploaded: "已上传",
  auditing: "审核中",
  done: "已完成",
  failed: "失败",
};

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    documents.value = await api.listDocuments();
  } catch {
    documents.value = [];
    error.value = "加载文档列表失败，请稍后重试";
  } finally {
    loading.value = false;
  }
}

async function onFiles(files: FileList | null) {
  if (!files?.length || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    for (const file of Array.from(files)) {
      await api.uploadDocument(file);
    }
    await load();
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : "上传失败";
  } finally {
    busy.value = false;
    if (fileInput.value) fileInput.value.value = "";
  }
}

function onDrop(e: DragEvent) {
  dragging.value = false;
  onFiles(e.dataTransfer?.files ?? null);
}

function openPicker() {
  if (!busy.value) fileInput.value?.click();
}

function startAudit(doc: DocumentItem) {
  router.push({ path: "/report", query: { documentId: doc.id, filename: doc.filename } });
}

onMounted(load);
</script>

<template>
  <div class="page">
    <h1 class="title">上传合规文档</h1>
    <p class="desc">支持 PDF、Word、Excel、Markdown、纯文本。上传后可发起 AI 合规审核（SSE 流式输出）。</p>

    <div
      class="dropzone card"
      :class="{ dragging, busy }"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
      @click="openPicker"
    >
      <input
        ref="fileInput"
        type="file"
        :accept="accept"
        multiple
        hidden
        @change="onFiles(($event.target as HTMLInputElement).files)"
      />
      <div v-if="busy" class="drop-overlay">
        <span class="spinner"></span>
        <span>正在上传，请稍候…</span>
      </div>
      <template v-else>
        <div class="drop-icon">📁</div>
        <div class="drop-title">拖拽文件到此处，或点击选择</div>
        <div class="drop-hint">PDF · DOCX · XLSX · MD · TXT · 单文件建议 ≤ 20MB</div>
      </template>
    </div>

    <p v-if="error" class="error-banner">{{ error }}</p>

    <section v-if="loading" class="list-section card" aria-busy="true">
      <h2>已上传文档</h2>
      <div class="skeleton-table">
        <div v-for="n in 3" :key="n" class="skeleton-row">
          <div class="skeleton skeleton-cell wide"></div>
          <div class="skeleton skeleton-cell"></div>
          <div class="skeleton skeleton-cell"></div>
          <div class="skeleton skeleton-cell wide"></div>
          <div class="skeleton skeleton-cell btn"></div>
        </div>
      </div>
    </section>

    <section v-else-if="documents.length" class="list-section card">
      <div class="list-head">
        <h2>已上传文档</h2>
        <span class="count">{{ documents.length }} 个文件</span>
      </div>
      <table class="doc-table">
        <thead>
          <tr>
            <th>文件名</th>
            <th>大小</th>
            <th>状态</th>
            <th>上传时间</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="doc in documents" :key="doc.id">
            <td class="filename">{{ doc.filename }}</td>
            <td>{{ formatSize(doc.size) }}</td>
            <td>
              <span :class="['status-pill', `status-${doc.status}`]">
                <span v-if="doc.status === 'auditing'" class="spinner spinner-sm"></span>
                {{ statusLabel[doc.status] }}
              </span>
            </td>
            <td class="muted-cell">{{ doc.uploadedAt }}</td>
            <td>
              <button
                class="btn btn-primary btn-sm"
                :disabled="doc.status === 'auditing' || busy"
                @click.stop="startAudit(doc)"
              >
                开始审核
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-else class="empty card">
      <div class="empty-state">
        <div class="empty-state-icon">📄</div>
        <p class="empty-state-title">还没有合规文档</p>
        <p class="empty-state-desc">上传合同、制度或政策文件后，即可发起 AI 合规审核并生成风险报告。</p>
        <button class="btn btn-primary" :disabled="busy" @click="openPicker">选择文件上传</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page { max-width: 960px; margin: 0 auto; }
.title { margin: 0 0 8px; font-size: 22px; }
.desc { margin: 0 0 20px; color: var(--muted); }
.dropzone {
  position: relative;
  padding: 48px 24px;
  text-align: center;
  cursor: pointer;
  border: 2px dashed var(--border);
  transition: border-color 0.15s, background 0.15s;
}
.dropzone.dragging, .dropzone:hover:not(.busy) {
  border-color: var(--primary);
  background: var(--primary-soft);
}
.dropzone.busy { cursor: wait; border-style: solid; }
.drop-overlay {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: var(--primary);
  font-weight: 600;
}
.drop-icon { font-size: 40px; margin-bottom: 8px; }
.drop-title { font-weight: 600; font-size: 16px; }
.drop-hint { color: var(--muted); font-size: 13px; margin-top: 4px; }
.error-banner {
  padding: 12px 16px;
  background: #fee2e2;
  color: #991b1b;
  border-radius: var(--radius);
  margin-top: 16px;
}
.list-section { margin-top: 24px; padding: 20px; }
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.list-section h2 { margin: 0; font-size: 16px; }
.count { font-size: 12px; color: var(--muted); font-weight: 600; }
.doc-table { width: 100%; border-collapse: collapse; }
.doc-table th, .doc-table td {
  padding: 12px;
  text-align: left;
  border-bottom: 1px solid var(--border);
}
.doc-table th { color: var(--muted); font-weight: 600; font-size: 12px; }
.filename { font-weight: 600; max-width: 280px; word-break: break-all; }
.muted-cell { color: var(--muted); font-size: 13px; }
.btn-sm { padding: 6px 12px; font-size: 13px; }
.spinner-sm { width: 12px; height: 12px; border-width: 2px; }
.skeleton-table { display: flex; flex-direction: column; gap: 12px; }
.skeleton-row { display: grid; grid-template-columns: 2fr 1fr 1fr 1.5fr 100px; gap: 12px; }
.skeleton-cell { height: 36px; }
.skeleton-cell.wide { min-width: 0; }
.skeleton-cell.btn { width: 88px; }
.empty { margin-top: 24px; }
</style>
