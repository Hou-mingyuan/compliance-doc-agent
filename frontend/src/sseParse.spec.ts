import { describe, expect, it } from "vitest";
import { parseSseBuffer, parseSseData } from "./sseParse";

describe("parseSseBuffer", () => {
  it("parses a single SSE frame", () => {
    const { frames, remainder } = parseSseBuffer(
      "event: start\ndata: {\"auditId\":\"a1\"}\n\n",
    );
    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe("start");
    expect(frames[0].data).toBe('{"auditId":"a1"}');
    expect(remainder).toBe("");
  });

  it("buffers incomplete frames", () => {
    const { frames, remainder } = parseSseBuffer("event: token\ndata: {\"text\":\"hi\"}");
    expect(frames).toHaveLength(0);
    expect(remainder).toContain("event: token");
  });

  it("parses multiple frames in one chunk", () => {
    const chunk =
      "event: finding\ndata: {\"rule\":\"R-1\"}\n\n" +
      "event: done\ndata: {\"auditId\":\"x\"}\n\n" +
      "event: token\ndata: {\"text\":\"tail";
    const { frames, remainder } = parseSseBuffer(chunk);
    expect(frames).toHaveLength(2);
    expect(frames[0].event).toBe("finding");
    expect(frames[1].event).toBe("done");
    expect(remainder).toContain("token");
  });
});

describe("parseSseData", () => {
  it("parses JSON payloads", () => {
    expect(parseSseData<{ auditId: string }>('{"auditId":"abc"}')).toEqual({ auditId: "abc" });
  });

  it("returns raw string for non-JSON", () => {
    expect(parseSseData("plain-text")).toBe("plain-text");
  });

  it("returns empty string unchanged", () => {
    expect(parseSseData("")).toBe("");
  });
});

describe("parseSseBuffer edge cases", () => {
  it("ignores frames without data payload", () => {
    const { frames } = parseSseBuffer("event: ping\n\n");
    expect(frames).toHaveLength(0);
  });

  it("handles CRLF delimiters", () => {
    const { frames } = parseSseBuffer("event: done\r\ndata: {}\r\n\r\n");
    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe("done");
  });
});
