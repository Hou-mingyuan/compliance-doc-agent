export interface HighlightFinding {
  severity: "critical" | "high" | "medium" | "low" | "info";
  rule: string;
  description: string;
  kind?: "hit" | "missing";
  matchStart?: number | null;
  matchEnd?: number | null;
}

export interface HighlightSpan {
  start: number;
  end: number;
  severity: HighlightFinding["severity"];
  rule: string;
  kind: "hit" | "missing";
}

export function collectHighlightSpans(findings: HighlightFinding[]): HighlightSpan[] {
  return findings
    .filter((f) => f.matchStart != null && f.matchStart >= 0 && f.matchEnd != null && f.matchEnd > f.matchStart)
    .map((f) => ({
      start: f.matchStart!,
      end: f.matchEnd!,
      severity: f.severity,
      rule: f.rule,
      kind: (f.kind ?? "hit") as "hit" | "missing",
    }))
    .sort((a, b) => a.start - b.start);
}

/** 将文档正文转为带风险高亮的 HTML（已 escape，可 v-html） */
export function buildHighlightedHtml(
  content: string,
  findings: HighlightFinding[],
  activeIndex = -1,
  recentStart: number | null = null,
): string {
  if (!content) return "";
  const spans = collectHighlightSpans(findings);
  const body = spans.length ? renderHitSpans(content, spans, activeIndex, recentStart) : escapeHtml(content);
  const inserts = buildMissingInsertions(findings);
  return inserts ? `${body}${inserts}` : body;
}

function renderHitSpans(
  content: string,
  spans: HighlightSpan[],
  activeIndex: number,
  recentStart: number | null,
): string {
  const parts: string[] = [];
  let cursor = 0;
  spans.forEach((span, i) => {
    if (span.start > cursor) {
      parts.push(escapeHtml(content.slice(cursor, span.start)));
    }
    const cls = [
      "hl",
      `hl-${span.severity}`,
      i === activeIndex ? "hl-active" : "",
      span.start === recentStart ? "hl-recent" : "",
    ]
      .filter(Boolean)
      .join(" ");
    const title = escapeAttr(span.rule);
    parts.push(
      `<mark class="${cls}" data-idx="${i}" data-start="${span.start}" title="${title}">${escapeHtml(content.slice(span.start, span.end))}</mark>`,
    );
    cursor = Math.max(cursor, span.end);
  });
  if (cursor < content.length) {
    parts.push(escapeHtml(content.slice(cursor)));
  }
  return parts.join("");
}

/** 缺失条款以 diff 插入行展示在正文末尾 */
export function buildMissingInsertions(findings: HighlightFinding[]): string {
  const missing = findings.filter((f) => f.kind === "missing");
  if (!missing.length) return "";
  return missing
    .map(
      (f) =>
        `<div class="diff-insert hl-missing" data-rule="${escapeAttr(f.rule)}">` +
        `<span class="diff-gutter">+</span>` +
        `<span class="diff-body"><strong>${escapeHtml(f.rule)}</strong> — ${escapeHtml(f.description)}</span>` +
        `</div>`,
    )
    .join("");
}

function escapeHtml(s: string) {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function escapeAttr(s: string) {
  return escapeHtml(s).replace(/'/g, "&#39;");
}
