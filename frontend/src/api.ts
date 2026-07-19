/** REST + SSE 封装，开发环境由 Vite proxy 转发至后端 8090 */

import { parseSseBuffer, parseSseData } from "./sseParse";

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface DocumentItem {
  id: string;
  filename: string;
  size: number;
  contentType: string;
  status: "uploaded" | "auditing" | "done" | "failed";
  uploadedAt: string;
}

interface UploadResponseDto {
  id: number;
  title: string;
  docType: string;
  format: string;
  status: string;
  contentLength: number;
  chunkCount: number;
  createdAt: string;
}

function mapUploadStatus(status: string): DocumentItem["status"] {
  switch (status?.toUpperCase()) {
    case "AUDITING":
      return "auditing";
    case "DONE":
      return "done";
    case "FAILED":
      return "failed";
    default:
      return "uploaded";
  }
}

function toDocumentItem(d: UploadResponseDto): DocumentItem {
  const ext = d.format ? `.${d.format}` : "";
  return {
    id: String(d.id),
    filename: d.title.includes(".") ? d.title : `${d.title}${ext}`,
    size: d.contentLength,
    contentType: d.format || "text/plain",
    status: mapUploadStatus(d.status),
    uploadedAt: d.createdAt,
  };
}

export interface AuditFinding {
  severity: "critical" | "high" | "medium" | "low" | "info";
  rule: string;
  description: string;
  location?: string;
  kind?: "hit" | "missing";
  matchedText?: string;
  matchStart?: number;
  matchEnd?: number;
}

export interface DocumentContent {
  id: string;
  title: string;
  docType: string;
  status: string;
  content: string;
  contentLength: number;
  createdAt: string;
}

export interface AuditReport {
  id: string;
  documentId: string;
  filename: string;
  status: "running" | "done" | "failed";
  summary?: string;
  findings: AuditFinding[];
  narrative: string;
  createdAt: string;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const resp = await fetch(path, options);
  const json = (await resp.json()) as ApiResponse<T>;
  if (json.code !== 0) {
    throw new Error(json.message || "请求失败");
  }
  return json.data;
}

export const api = {
  listDocuments: () => request<DocumentItem[]>("/api/documents"),
  getDocument: (id: string) => request<DocumentContent>(`/api/documents/${encodeURIComponent(id)}`),
  uploadDocument: async (file: File): Promise<DocumentItem> => {
    const form = new FormData();
    form.append("file", file);
    const resp = await fetch("/api/documents/upload", { method: "POST", body: form });
    const json = (await resp.json()) as ApiResponse<UploadResponseDto>;
    if (json.code !== 0) throw new Error(json.message || "上传失败");
    return toDocumentItem(json.data);
  },
  getReport: (auditId: string) => request<AuditReport>(`/api/audit/${auditId}`),
};

export type SseHandler = (event: string, data: unknown) => void;

/** 发起合规审核 SSE 流。后端：POST /api/compliance/audit/stream/{documentId} */
export async function streamAudit(
  body: { documentId: string },
  onEvent: SseHandler,
  signal?: AbortSignal,
): Promise<void> {
  const resp = await fetch(`/api/compliance/audit/stream/${encodeURIComponent(body.documentId)}`, {
    method: "POST",
    headers: { Accept: "text/event-stream" },
    signal,
  });
  if (!resp.ok) {
    throw new Error(`审核请求失败 (${resp.status})`);
  }
  if (!resp.body) throw new Error("浏览器不支持流式响应");

  const reader = resp.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const { frames, remainder } = parseSseBuffer(buffer);
    buffer = remainder;
    for (const frame of frames) {
      onEvent(frame.event, parseSseData(frame.data));
    }
  }
}
