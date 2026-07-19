#!/usr/bin/env python3
"""SSE audit Mock soak — POST /api/compliance/audit/stream/{docId} stability probe."""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SAMPLE = ROOT / "backend/src/main/resources/samples/合同条款片段.txt"


def http_json(method: str, url: str, body: dict | None = None, timeout: float = 30) -> dict:
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("code") != 0:
        raise RuntimeError(payload.get("message") or "API error")
    return payload["data"]


def ensure_document(base: str) -> str:
    try:
        docs = http_json("GET", f"{base}/api/documents")
        if docs:
            return str(docs[0]["id"])
    except Exception:
        pass
    if not SAMPLE.is_file():
        raise FileNotFoundError(f"Sample not found: {SAMPLE}")
    content = SAMPLE.read_text(encoding="utf-8")
    created = http_json(
        "POST",
        f"{base}/api/compliance/analyze",
        {"title": "soak-样例", "docType": "CONTRACT", "content": content},
    )
    return str(created["documentId"])


def stream_audit(base: str, doc_id: str) -> dict:
    url = f"{base}/api/compliance/audit/stream/{doc_id}"
    req = urllib.request.Request(
        url,
        method="POST",
        headers={"Accept": "text/event-stream"},
    )
    started = time.perf_counter()
    first_event_ms: float | None = None
    counts: dict[str, int] = {}
    errors: list[str] = []

    with urllib.request.urlopen(req, timeout=120) as resp:
        buffer = ""
        while True:
            chunk = resp.read(4096)
            if not chunk:
                break
            buffer += chunk.decode("utf-8", errors="replace")
            while "\n\n" in buffer:
                raw, buffer = buffer.split("\n\n", 1)
                event = "message"
                data = ""
                for line in raw.split("\n"):
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        data += line[5:].strip()
                if not data:
                    continue
                if first_event_ms is None:
                    first_event_ms = (time.perf_counter() - started) * 1000
                counts[event] = counts.get(event, 0) + 1
                if event == "error":
                    errors.append(data)

    elapsed_ms = (time.perf_counter() - started) * 1000
    return {
        "documentId": doc_id,
        "elapsedMs": round(elapsed_ms, 1),
        "ttfbMs": round(first_event_ms or 0, 1),
        "eventCounts": counts,
        "errors": errors,
        "ok": counts.get("start", 0) >= 1 and counts.get("done", 0) >= 1 and counts.get("finding", 0) >= 2,
    }


def _percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(round((pct / 100) * len(ordered) - 1))))
    return ordered[idx]


def main() -> int:
    parser = argparse.ArgumentParser(description="Compliance SSE audit Mock soak")
    parser.add_argument("--base", default="http://127.0.0.1:8080", help="Backend base URL")
    parser.add_argument("--duration", type=int, default=60, help="Wall-clock soak seconds")
    parser.add_argument("--document-id", default="", help="Reuse existing document id")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    health = urllib.request.urlopen(f"{base}/api/health", timeout=10)
    if health.status != 200:
        print("health check failed", file=sys.stderr)
        return 1

    doc_id = args.document_id or ensure_document(base)
    deadline = time.perf_counter() + args.duration
    runs: list[dict] = []

    while time.perf_counter() < deadline:
        run = stream_audit(base, doc_id)
        runs.append(run)
        print(
            f"run={len(runs)} ok={run['ok']} ttfb_ms={run['ttfbMs']} "
            f"elapsed_ms={run['elapsedMs']} events={run['eventCounts']}"
        )
        if not run["ok"]:
            print(json.dumps(run, ensure_ascii=False, indent=2), file=sys.stderr)
            return 1
        if time.perf_counter() >= deadline:
            break
        time.sleep(0.5)

    summary = {
        "base": base,
        "documentId": doc_id,
        "durationSec": args.duration,
        "runs": len(runs),
        "allOk": all(r["ok"] for r in runs),
        "avgTtfbMs": round(sum(r["ttfbMs"] for r in runs) / len(runs), 1),
        "avgElapsedMs": round(sum(r["elapsedMs"] for r in runs) / len(runs), 1),
        "maxElapsedMs": round(max(r["elapsedMs"] for r in runs), 1),
        "p95ElapsedMs": round(_percentile([r["elapsedMs"] for r in runs], 95), 1),
        "p95TtfbMs": round(_percentile([r["ttfbMs"] for r in runs], 95), 1),
        "errorRuns": sum(1 for r in runs if not r["ok"]),
    }
    print("SOAK_SUMMARY=" + json.dumps(summary, ensure_ascii=False))
    return 0 if summary["allOk"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
