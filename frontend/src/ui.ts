export const reviewStatusLabel: Record<string, string> = {
  CREATED: "已创建",
  RUNNING: "审核中",
  PENDING_REVIEW: "待复核",
  REMEDIATION: "整改中",
  RECHECK: "待复审",
  APPROVED: "已批准",
  CANCELLED: "已取消",
  FAILED: "失败",
};

export const findingStatusLabel: Record<string, string> = {
  OPEN: "待判断",
  CONFIRMED: "已确认",
  REMEDIATION_REQUIRED: "整改中",
  FALSE_POSITIVE: "误报",
  RESOLVED: "已解决",
};

export const remediationStatusLabel: Record<string, string> = {
  OPEN: "待开始",
  IN_PROGRESS: "处理中",
  EVIDENCE_SUBMITTED: "待验收",
  REJECTED: "已退回",
  VERIFIED: "已验证",
  CLOSED: "已关闭",
  REOPENED: "已重开",
};

export const severityLabel: Record<string, string> = {
  CRITICAL: "严重",
  HIGH: "高",
  MEDIUM: "中",
  LOW: "低",
  INFO: "提示",
};

export const documentTypeLabel: Record<string, string> = {
  CONTRACT: "合同",
  POLICY: "内部制度",
  PRIVACY: "隐私条款",
  DISCLOSURE: "披露材料",
  GENERAL: "通用文档",
};

export const entityTypeLabel: Record<string, string> = {
  SUBJECT: "主体",
  AMOUNT: "金额",
  DATE: "日期",
  RESPONSIBILITY: "责任义务",
  AUTO_RENEWAL: "自动续期",
  PERSONAL_ID: "身份证号",
};

export interface SourceLocation {
  pageNo?: number | null;
  sectionTitle?: string | null;
  paragraphNo?: number | null;
  matchStart?: number | null;
  matchEnd?: number | null;
}

export function formatSourceLocation(value: SourceLocation): string {
  const parts: string[] = [];
  if (value.pageNo) parts.push(`第 ${value.pageNo} 页`);
  const section = value.sectionTitle?.trim();
  if (section) parts.push(section);
  if (value.paragraphNo) parts.push(`第 ${value.paragraphNo} 段`);
  if (value.matchStart != null) {
    const end = value.matchEnd ?? value.matchStart;
    parts.push(`字符 ${value.matchStart}–${end}`);
  }
  return parts.join(" · ");
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function shortKey(value: string | null | undefined, keep = 10): string {
  if (!value) return "—";
  return value.length > keep ? `${value.slice(0, keep)}…` : value;
}

export function statusClass(value: string): string {
  return `status-${value.toLowerCase().replaceAll("_", "-")}`;
}

export function severityClass(value: string): string {
  return `severity-${value.toLowerCase()}`;
}

export function localizeRiskSummary(value: string | null | undefined): string {
  if (!value) return "";
  return value
    .replace(/共\s*(\d+)\s*项有效风险[，,]\s*综合分/g, "规则初筛命中 $1 项，初始综合分")
    .replace(/等级\s+(CRITICAL|HIGH|MEDIUM|LOW|INFO)\b/gi, (_, level: string) =>
      `等级${severityLabel[level.toUpperCase()] || level}`);
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}
