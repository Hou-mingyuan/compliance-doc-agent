/** 纯函数：从 SSE 文本缓冲解析完整帧（双换行分隔） */
export interface SseFrame {
  event: string;
  data: string;
}

export function parseSseBuffer(buffer: string): { frames: SseFrame[]; remainder: string } {
  const frames: SseFrame[] = [];
  let rest = buffer.replace(/\r\n/g, "\n");
  let idx: number;
  while ((idx = rest.indexOf("\n\n")) >= 0) {
    const raw = rest.slice(0, idx);
    rest = rest.slice(idx + 2);
    let event = "message";
    let data = "";
    for (const line of raw.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data += line.slice(5).trim();
    }
    if (data) frames.push({ event, data });
  }
  return { frames, remainder: rest };
}

export function parseSseData<T = unknown>(data: string): T | string {
  try {
    return JSON.parse(data) as T;
  } catch {
    return data;
  }
}
