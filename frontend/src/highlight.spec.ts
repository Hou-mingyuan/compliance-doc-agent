import { describe, expect, it } from "vitest";
import type { AuditFinding } from "./api";
import { buildHighlightedHtml, buildMissingInsertions, collectHighlightSpans } from "./highlight";

const findings: AuditFinding[] = [
  {
    severity: "high",
    rule: "R-CON-003",
    description: "禁止无限连带责任",
    kind: "hit",
    matchStart: 10,
    matchEnd: 18,
  },
  {
    severity: "medium",
    rule: "R-PII-001",
    description: "疑似身份证号",
    kind: "hit",
    matchStart: 30,
    matchEnd: 48,
  },
  {
    severity: "critical",
    rule: "R-MISS-001",
    description: "缺少保密条款",
    kind: "missing",
  },
];

describe("collectHighlightSpans", () => {
  it("keeps only valid hit spans sorted by start", () => {
    const spans = collectHighlightSpans(findings);
    expect(spans).toHaveLength(2);
    expect(spans[0].start).toBe(10);
    expect(spans[1].start).toBe(30);
    expect(spans[0].kind).toBe("hit");
  });
});

describe("buildMissingInsertions", () => {
  it("renders diff-insert rows for missing clauses", () => {
    const html = buildMissingInsertions(findings);
    expect(html).toContain('class="diff-insert');
    expect(html).toContain("R-MISS-001");
    expect(html).toContain("缺少保密条款");
  });
});

describe("buildHighlightedHtml", () => {
  const content = "0123456789012345678901234567890123456789012345678";

  it("escapes plain text and wraps hit ranges", () => {
    const html = buildHighlightedHtml(content, findings);
    expect(html).toContain("<mark");
    expect(html).toContain('class="hl hl-high');
    expect(html).toContain("R-CON-003");
    expect(html).toContain('class="diff-insert');
  });

  it("marks recent finding with hl-recent", () => {
    const html = buildHighlightedHtml(content, findings, -1, 10);
    expect(html).toContain("hl-recent");
  });

  it("returns escaped plain text when no hit spans", () => {
    const html = buildHighlightedHtml("plain <text>", [{ severity: "low", rule: "R-X", description: "d", kind: "missing" }]);
    expect(html).toContain("plain &lt;text&gt;");
    expect(html).not.toContain("<mark");
  });

  it("handles overlapping spans by advancing cursor", () => {
    const overlap: AuditFinding[] = [
      { severity: "high", rule: "A", description: "a", kind: "hit", matchStart: 5, matchEnd: 15 },
      { severity: "medium", rule: "B", description: "b", kind: "hit", matchStart: 10, matchEnd: 20 },
    ];
    const html = buildHighlightedHtml("012345678901234567890", overlap);
    expect(html.match(/<mark/g)?.length).toBe(2);
  });
});
