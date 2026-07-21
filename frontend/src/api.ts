import { authHeader } from "./auth";
import { parseSseBuffer, parseSseData } from "./sseParse";

export interface ApiEnvelope<T> { code: number; message: string; data: T }

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: number,
    public readonly payload: unknown = null,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface DocumentItem {
  id: string;
  filename: string;
  size: number;
  contentType: string;
  status: "uploaded" | "auditing" | "pending_review" | "remediation" | "done" | "cancelled" | "failed";
  uploadedAt: string;
  docType: string;
  tenantId: string;
  pageCount: number;
  versionNo: number;
  parentDocumentId: number | null;
}

export interface UploadResult {
  id: number;
  title: string;
  docType: string;
  format: string;
  status: string;
  contentLength: number;
  chunkCount: number;
  pageCount: number;
  versionNo: number;
  parentDocumentId: number | null;
  duplicate: boolean;
  createdAt: string;
}

export interface DocumentContent {
  id: number;
  title: string;
  sourceFilename: string;
  docType: string;
  format: string;
  status: string;
  content: string;
  contentLength: number;
  pageCount: number;
  versionNo: number;
  parentDocumentId: number | null;
  createdAt: string;
}

export interface ReviewRecord {
  id: number;
  reviewKey: string;
  tenantId: string;
  documentId: number;
  createdBy: string;
  status: string;
  rulePackVersion: string;
  llmProvider: string;
  riskScore: number;
  summary: string | null;
  cancelRequested: boolean;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Finding {
  id: number;
  findingKey: string;
  tenantId: string;
  reviewId: number;
  documentId: number;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO";
  title: string;
  description: string;
  sourceType: string;
  ruleCode: string | null;
  evidenceText: string | null;
  suggestion: string | null;
  chunkId: number | null;
  pageNo: number | null;
  sectionTitle: string | null;
  paragraphNo: number | null;
  matchStart: number | null;
  matchEnd: number | null;
  confidence: number;
  status: string;
  reviewerComment: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Citation {
  regulationCode: string;
  relevanceScore: number;
  excerpt: string;
  title: string;
  versionLabel: string;
  effectiveDate: string;
  expiryDate: string | null;
  scope: string;
  sourceName: string;
  sourceUrl: string | null;
  articleNo: string;
  demoData: boolean;
}

export interface EntityRecord {
  id: number;
  entityKey: string;
  tenantId: string;
  reviewId: number;
  documentId: number;
  type: string;
  value: string;
  normalizedValue: string;
  start: number;
  end: number;
  chunkId: number | null;
  pageNo: number | null;
  sectionTitle: string | null;
  paragraphNo: number | null;
  confidence: number;
  createdAt: string;
}

export interface RemediationTask {
  id: number;
  taskKey: string;
  tenantId: string;
  reviewId: number;
  findingId: number;
  assigneeId: string;
  dueDate: string;
  status: string;
  description: string;
  reviewComment: string | null;
  createdBy: string;
  versionNo: number;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
}

export interface EvidenceRecord {
  id: number;
  taskId: number;
  submittedBy: string;
  evidenceText: string;
  submittedAt: string;
}

export interface ToolExecution {
  executionKey: string;
  toolName: string;
  success: boolean;
  errorCode: string | null;
  argsDigest: string;
  summary: string;
  durationMs: number;
  actorId: string;
  createdAt: string;
}

export interface ReportMetadata {
  reportKey: string;
  reviewId: number;
  versionNo: number;
  format: string;
  fileName: string;
  sourceDigest: string;
  sha256: string;
  sizeBytes: number;
  createdBy: string;
  createdAt: string;
  downloadUrl: string;
}

export interface ReviewDetail {
  review: ReviewRecord;
  document: DocumentContent;
  findings: Array<{ finding: Finding; citations: Citation[] }>;
  entities: EntityRecord[];
  remediations: RemediationTask[];
  toolExecutions: ToolExecution[];
  reports: ReportMetadata[];
}

export interface RegulationEntry {
  code: string;
  title: string;
  versionLabel: string;
  effectiveDate: string;
  expiryDate: string | null;
  scope: string;
  sourceName: string;
  sourceUrl: string | null;
  articleNo: string;
  content: string;
  keywords: string;
  demoData: boolean;
}

export interface RegulationMatch { entry: RegulationEntry; relevanceScore: number; excerpt: string }

export interface AuditEvent {
  eventKey: string;
  tenantId: string;
  actorId: string;
  actorRole: string;
  action: string;
  resourceType: string;
  resourceId: string;
  fromState: string | null;
  toState: string | null;
  detailsJson: string;
  previousHash: string;
  eventHash: string;
  createdAt: string;
}

export interface ChainVerification { valid: boolean; eventCount: number; brokenAt: string | null; latestHash: string }
export interface Assignee { userId: string; tenantId: string; role: string }

const inFlightGets = new Map<string, Promise<unknown>>();

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => {
      window.clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    }, { once: true });
  });
}

async function fetchEnvelope<T>(path: string, options: RequestInit, retries: number): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, options);
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    if (retries > 0) {
      await sleep(250 * (3 - retries), options.signal ?? undefined);
      return fetchEnvelope(path, options, retries - 1);
    }
    throw new ApiError("无法连接后端服务", 0, 0);
  }
  const text = await response.text();
  let envelope: ApiEnvelope<T> | null = null;
  try { envelope = text ? JSON.parse(text) as ApiEnvelope<T> : null; } catch { /* normalized below */ }
  if (!response.ok || !envelope || envelope.code !== 0) {
    if (response.status === 401) window.dispatchEvent(new CustomEvent("compliance:auth-expired"));
    if (response.status >= 500 && retries > 0) {
      await sleep(250 * (3 - retries), options.signal ?? undefined);
      return fetchEnvelope(path, options, retries - 1);
    }
    throw new ApiError(envelope?.message || `请求失败 (${response.status})`, response.status, envelope?.code ?? response.status, envelope);
  }
  return envelope.data;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  Object.entries(authHeader()).forEach(([key, value]) => headers.set(key, value));
  if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  headers.set("Accept", "application/json");
  const method = (options.method || "GET").toUpperCase();
  const merged = { ...options, method, headers };
  if (method !== "GET") return fetchEnvelope<T>(path, merged, 0);
  if (options.signal) return fetchEnvelope<T>(path, merged, 2);
  const key = `${headers.get("Authorization") || "anon"}:${path}`;
  const existing = inFlightGets.get(key) as Promise<T> | undefined;
  if (existing) return existing;
  const pending = fetchEnvelope<T>(path, merged, 2).finally(() => inFlightGets.delete(key));
  inFlightGets.set(key, pending);
  return pending;
}

function json(method: string, value?: unknown, signal?: AbortSignal): RequestInit {
  return { method, body: value === undefined ? undefined : JSON.stringify(value), signal };
}

export const api = {
  listDocuments: (signal?: AbortSignal) => request<DocumentItem[]>("/api/documents", { signal }),
  getDocument: (id: number | string, signal?: AbortSignal) =>
    request<DocumentContent>(`/api/documents/${encodeURIComponent(id)}`, { signal }),
  getVersions: (id: number | string, signal?: AbortSignal) =>
    request<DocumentItem[]>(`/api/documents/${encodeURIComponent(id)}/versions`, { signal }),
  uploadDocument: (file: File, docType: string, signal?: AbortSignal) => {
    const form = new FormData();
    form.append("file", file);
    form.append("docType", docType);
    return request<UploadResult>("/api/documents/upload", { method: "POST", body: form, signal });
  },
  createVersion: (documentId: number | string, file: File, signal?: AbortSignal) => {
    const form = new FormData();
    form.append("file", file);
    return request<UploadResult>(`/api/documents/${encodeURIComponent(documentId)}/versions`,
      { method: "POST", body: form, signal });
  },
  listReviews: (signal?: AbortSignal) => request<ReviewRecord[]>("/api/reviews", { signal }),
  getReview: (reviewKey: string, signal?: AbortSignal) =>
    request<ReviewDetail>(`/api/reviews/${encodeURIComponent(reviewKey)}`, { signal }),
  cancelReview: (reviewKey: string) =>
    request<ReviewRecord>(`/api/reviews/${encodeURIComponent(reviewKey)}/cancel`, { method: "POST" }),
  reviewFinding: (findingKey: string, decision: "CONFIRM" | "FALSE_POSITIVE", comment: string) =>
    request<Finding>(`/api/findings/${encodeURIComponent(findingKey)}/review`, json("POST", { decision, comment })),
  approveReview: (reviewKey: string, comment: string) =>
    request<ReviewRecord>(`/api/reviews/${encodeURIComponent(reviewKey)}/approve`, json("POST", { comment })),
  listRemediations: (reviewId?: number, signal?: AbortSignal) =>
    request<RemediationTask[]>(`/api/remediations${reviewId ? `?reviewId=${reviewId}` : ""}`, { signal }),
  getRemediation: (taskKey: string, signal?: AbortSignal) =>
    request<{ task: RemediationTask; evidence: EvidenceRecord[] }>(
      `/api/remediations/${encodeURIComponent(taskKey)}`, { signal }),
  createRemediation: (value: { findingKey: string; assigneeId: string; dueDate: string; description: string }) =>
    request<RemediationTask>("/api/remediations", json("POST", value)),
  startRemediation: (taskKey: string) =>
    request<RemediationTask>(`/api/remediations/${encodeURIComponent(taskKey)}/start`, { method: "POST" }),
  submitEvidence: (taskKey: string, evidenceText: string) =>
    request<RemediationTask>(`/api/remediations/${encodeURIComponent(taskKey)}/evidence`, json("POST", { evidenceText })),
  reviewEvidence: (taskKey: string, approved: boolean, comment: string) =>
    request<RemediationTask>(`/api/remediations/${encodeURIComponent(taskKey)}/review`, json("POST", { approved, comment })),
  closeRemediation: (taskKey: string) =>
    request<RemediationTask>(`/api/remediations/${encodeURIComponent(taskKey)}/close`, { method: "POST" }),
  reopenRemediation: (taskKey: string, reason: string) =>
    request<RemediationTask>(`/api/remediations/${encodeURIComponent(taskKey)}/reopen`, json("POST", { reason })),
  listRegulations: (signal?: AbortSignal) => request<RegulationEntry[]>("/api/regulations", { signal }),
  searchRegulations: (query: string, scope: string, signal?: AbortSignal) =>
    request<RegulationMatch[]>(`/api/regulations/search?query=${encodeURIComponent(query)}&scope=${encodeURIComponent(scope)}`, { signal }),
  listAuditEvents: (limit = 100, signal?: AbortSignal) =>
    request<AuditEvent[]>(`/api/audit/events?limit=${limit}`, { signal }),
  verifyAudit: (tenantId?: string, signal?: AbortSignal) =>
    request<ChainVerification>(`/api/audit/verify${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ""}`, { signal }),
  listAssignees: (tenantId?: string, signal?: AbortSignal) =>
    request<Assignee[]>(`/api/auth/assignees${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ""}`, { signal }),
  generateReport: (reviewKey: string) => request<ReportMetadata>("/api/reports", json("POST", { reviewKey })),
  getReport: (reportKey: string, signal?: AbortSignal) =>
    request<ReportMetadata>(`/api/reports/${encodeURIComponent(reportKey)}`, { signal }),
  listReports: (reviewKey: string, signal?: AbortSignal) =>
    request<ReportMetadata[]>(`/api/reports/review/${encodeURIComponent(reviewKey)}`, { signal }),
  downloadReport: async (report: ReportMetadata): Promise<void> => {
    const response = await fetch(report.downloadUrl, { headers: { ...authHeader() } });
    if (!response.ok) {
      const payload = await response.json().catch(() => null) as { message?: string } | null;
      throw new ApiError(payload?.message || "报告下载失败", response.status, response.status, payload);
    }
    const blob = await response.blob();
    const href = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = href;
    anchor.download = report.fileName;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(href), 1_000);
  },
};

export interface StreamFinding {
  id: string;
  severity: "critical" | "high" | "medium" | "low" | "info";
  rule: string;
  description: string;
  location?: string;
  kind?: "hit" | "missing";
  matchedText?: string;
  matchStart?: number | null;
  matchEnd?: number | null;
  pageNo?: number | null;
  sectionTitle?: string | null;
  paragraphNo?: number | null;
  status?: string;
  suggestion?: string;
}

export type SseHandler = (event: string, data: Record<string, unknown>) => void;

export async function streamAudit(
  documentId: number | string,
  onEvent: SseHandler,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api/reviews/stream/${encodeURIComponent(documentId)}`, {
    method: "POST",
    headers: { Accept: "text/event-stream", ...authHeader() },
    signal,
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { message?: string } | null;
    throw new ApiError(payload?.message || `审核请求失败 (${response.status})`, response.status, response.status, payload);
  }
  if (!response.body) throw new ApiError("浏览器不支持流式响应", 0, 0);
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parsed = parseSseBuffer(buffer);
    buffer = parsed.remainder;
    parsed.frames.forEach((frame) => onEvent(frame.event, parseSseData(frame.data) as Record<string, unknown>));
  }
}
