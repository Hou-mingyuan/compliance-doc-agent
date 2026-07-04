/** REST + SSE 封装，约定后端运行在 8080（开发由 Vite proxy 转发） */

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

export interface AuditFinding {
  severity: "critical" | "high" | "medium" | "low" | "info";
  rule: string;
  description: string;
  location?: string;
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
  uploadDocument: async (file: File): Promise<DocumentItem> => {
    const form = new FormData();
    form.append("file", file);
    const resp = await fetch("/api/documents/upload", { method: "POST", body: form });
    const json = (await resp.json()) as ApiResponse<DocumentItem>;
    if (json.code !== 0) throw new Error(json.message || "上传失败");
    return json.data;
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
    let idx: number;
    while ((idx = buffer.indexOf("\n\n")) >= 0) {
      const raw = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      let event = "message";
      let data = "";
      for (const line of raw.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data += line.slice(5).trim();
      }
      if (data) {
        try {
          onEvent(event, JSON.parse(data));
        } catch {
          onEvent(event, data);
        }
      }
    }
  }
}
