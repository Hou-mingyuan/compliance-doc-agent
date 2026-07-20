import { describe, expect, it } from "vitest";
import { entityTypeLabel, formatSourceLocation, localizeRiskSummary } from "./ui";

describe("formatSourceLocation", () => {
  it("joins only populated location parts", () => {
    expect(formatSourceLocation({
      pageNo: 1,
      sectionTitle: "  ",
      paragraphNo: 7,
      matchStart: 88,
      matchEnd: 92,
    })).toBe("第 1 页 · 第 7 段 · 字符 88–92");
  });

  it("keeps zero-based character offsets", () => {
    expect(formatSourceLocation({ matchStart: 0, matchEnd: 4 })).toBe("字符 0–4");
  });
});

describe("entityTypeLabel", () => {
  it("localizes every deterministic extractor type", () => {
    expect(entityTypeLabel).toMatchObject({
      SUBJECT: "主体",
      AMOUNT: "金额",
      DATE: "日期",
      RESPONSIBILITY: "责任义务",
      AUTO_RENEWAL: "自动续期",
      PERSONAL_ID: "身份证号",
    });
  });
});

describe("localizeRiskSummary", () => {
  it("localizes legacy persisted severity labels without rewriting other content", () => {
    expect(localizeRiskSummary("共 3 项有效风险，综合分 62，等级 HIGH。"))
      .toBe("规则初筛命中 3 项，初始综合分 62，等级高。");
    expect(localizeRiskSummary("等级中，已是中文"))
      .toBe("等级中，已是中文");
  });
});
