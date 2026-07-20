#!/usr/bin/env python3
"""Run the zero-key acceptance workflow against a live Compliance Doc Agent."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime as dt
import json
import mimetypes
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / "samples"
DEFAULT_EVIDENCE = ROOT / "docs" / "evidence"

ACCOUNTS = {
    "user": ("user@demo.local", "demo-change-me"),
    "reviewer": ("reviewer@demo.local", "demo-change-me"),
    "compliance": ("compliance@demo.local", "demo-change-me"),
    "tenant_b": ("tenant-b@demo.local", "demo-change-me"),
}


class ApiFailure(RuntimeError):
    def __init__(self, status: int, message: str, payload: Any = None):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status
        self.payload = payload


class Client:
    def __init__(self, base_url: str):
        self.base = base_url.rstrip("/")

    @staticmethod
    def auth(role: str) -> str:
        username, password = ACCOUNTS[role]
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        return f"Basic {token}"

    def raw(
        self,
        method: str,
        path: str,
        role: str | None = None,
        body: bytes | None = None,
        content_type: str | None = None,
        accept: str = "application/json",
        timeout: float = 30,
    ) -> tuple[int, dict[str, str], bytes]:
        headers = {"Accept": accept}
        if role:
            headers["Authorization"] = self.auth(role)
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, dict(response.headers.items()), response.read()
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                payload = json.loads(raw.decode("utf-8"))
                message = payload.get("message", error.reason)
            except Exception:
                payload = None
                message = str(error.reason)
            raise ApiFailure(error.code, message, payload) from error
        except urllib.error.URLError as error:
            raise RuntimeError(f"无法连接 {self.base}：{error.reason}") from error

    def json(
        self,
        method: str,
        path: str,
        role: str | None = None,
        value: Any = None,
        timeout: float = 30,
    ) -> Any:
        body = None if value is None else json.dumps(value, ensure_ascii=False).encode("utf-8")
        _, _, raw = self.raw(
            method,
            path,
            role=role,
            body=body,
            content_type="application/json" if body is not None else None,
            timeout=timeout,
        )
        envelope = json.loads(raw.decode("utf-8"))
        if envelope.get("code") != 0:
            raise ApiFailure(envelope.get("code", 500), envelope.get("message", "业务请求失败"), envelope)
        return envelope.get("data")

    def multipart(
        self,
        path: str,
        role: str,
        filename: str,
        content: bytes,
        fields: dict[str, str] | None = None,
    ) -> Any:
        boundary = f"----ComplianceBoundary{uuid.uuid4().hex}"
        parts: list[bytes] = []
        for key, value in (fields or {}).items():
            parts.extend([
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode(),
                value.encode("utf-8"),
                b"\r\n",
            ])
        media_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        parts.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
            f"Content-Type: {media_type}\r\n\r\n".encode(),
            content,
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ])
        _, _, raw = self.raw(
            "POST",
            path,
            role=role,
            body=b"".join(parts),
            content_type=f"multipart/form-data; boundary={boundary}",
            timeout=60,
        )
        envelope = json.loads(raw.decode("utf-8"))
        if envelope.get("code") != 0:
            raise ApiFailure(envelope.get("code", 500), envelope.get("message", "上传失败"), envelope)
        return envelope["data"]

    def stream_review(
        self,
        document_id: int,
        role: str = "user",
        on_event: Callable[[str, dict[str, Any]], None] | None = None,
    ) -> dict[str, Any]:
        request = urllib.request.Request(
            f"{self.base}/api/reviews/stream/{document_id}",
            data=b"",
            headers={"Accept": "text/event-stream", "Authorization": self.auth(role)},
            method="POST",
        )
        events: list[dict[str, Any]] = []
        event_name = "message"
        data_lines: list[str] = []
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                for raw_line in response:
                    line = raw_line.decode("utf-8").rstrip("\r\n")
                    if not line:
                        if data_lines:
                            text = "".join(data_lines)
                            try:
                                payload = json.loads(text)
                            except json.JSONDecodeError:
                                payload = {"text": text}
                            events.append({"event": event_name, "data": payload})
                            if on_event:
                                on_event(event_name, payload)
                            if event_name == "error":
                                raise RuntimeError(payload.get("message", "SSE 审核失败"))
                        event_name = "message"
                        data_lines = []
                    elif line.startswith("event:"):
                        event_name = line[6:].strip()
                    elif line.startswith("data:"):
                        data_lines.append(line[5:].strip())
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                payload = json.loads(raw.decode("utf-8"))
            except Exception:
                payload = None
            raise ApiFailure(error.code, (payload or {}).get("message", error.reason), payload) from error
        review_key = next(
            (
                str(item["data"].get("reviewId") or item["data"].get("auditId"))
                for item in events
                if item["event"] in {"done", "start"} and (item["data"].get("reviewId") or item["data"].get("auditId"))
            ),
            "",
        )
        return {"reviewKey": review_key, "events": events}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def expect_status(action: Callable[[], Any], expected: int, label: str) -> None:
    try:
        action()
    except ApiFailure as error:
        require(error.status == expected, f"{label}：期望 HTTP {expected}，实际 {error.status}")
        return
    raise AssertionError(f"{label}：请求意外成功")


def evaluate_case(
    client: Client,
    case: dict[str, Any],
    document_id: int,
    stream: dict[str, Any],
) -> dict[str, Any]:
    review_key = stream["reviewKey"]
    require(review_key, f"{case['file']} 未收到 reviewKey")
    detail = client.json("GET", f"/api/reviews/{urllib.parse.quote(review_key)}", "user")
    findings = [item["finding"] for item in detail["findings"]]
    actual_codes = {item.get("ruleCode") for item in findings if item.get("ruleCode")}
    expected_codes = set(case.get("expectedRuleCodes", []))
    forbidden_codes = set(case.get("forbiddenRuleCodes", []))
    require(expected_codes <= actual_codes, f"{case['file']} 漏检 {sorted(expected_codes - actual_codes)}")
    require(not (forbidden_codes & actual_codes), f"{case['file']} 误报 {sorted(forbidden_codes & actual_codes)}")

    citations = {
        citation["regulationCode"]
        for item in detail["findings"]
        for citation in item.get("citations", [])
    }
    expected_citations = set(case.get("expectedCitationCodes", []))
    require(expected_citations <= citations, f"{case['file']} 法规引用缺失 {sorted(expected_citations - citations)}")

    entity_types = {item["type"] for item in detail["entities"]}
    expected_entities = set(case.get("expectedEntities", []))
    require(expected_entities <= entity_types, f"{case['file']} 实体缺失 {sorted(expected_entities - entity_types)}")

    evidence_text = "\n".join(str(item.get("evidenceText") or "") for item in findings)
    for forbidden in case.get("forbiddenEvidence", []):
        require(forbidden not in evidence_text, f"{case['file']} 证据未脱敏：{forbidden}")

    narrative = "".join(
        str(item["data"].get("text") or "")
        for item in stream["events"]
        if item["event"] == "narrative"
    )
    for forbidden in case.get("forbiddenNarrative", []):
        require(forbidden not in narrative, f"{case['file']} 执行了正文提示词")

    tool_names = {item["toolName"] for item in detail["toolExecutions"]}
    require({"check_rules", "extract_entities", "summarize_risks"} <= tool_names,
            f"{case['file']} 缺少核心工具轨迹")
    if expected_codes:
        require("search_regulation" in tool_names, f"{case['file']} 缺少法规检索轨迹")
    if any(item.get("matchStart") is not None for item in findings):
        require("get_document_section" in tool_names, f"{case['file']} 命中项缺少原文读取轨迹")
    if case["file"] == "contract-v2-risky.txt":
        require("compare_clause" in tool_names, "版本文档未执行 compare_clause")

    located_hits = [
        item for item in findings
        if item.get("matchStart") is not None and item.get("chunkId") is not None
    ]
    require(len(located_hits) == sum(1 for item in findings if item.get("matchStart") is not None),
            f"{case['file']} 存在无法回到原文块的命中")

    return {
        "file": case["file"],
        "docType": case["docType"],
        "documentId": document_id,
        "reviewKey": review_key,
        "status": detail["review"]["status"],
        "riskScore": detail["review"]["riskScore"],
        "expectedRuleCodes": sorted(expected_codes),
        "actualRuleCodes": sorted(actual_codes),
        "citationCodes": sorted(citations),
        "entityTypes": sorted(entity_types),
        "toolNames": sorted(tool_names),
        "locatedHitCount": len(located_hits),
    }


def exercise_cancellation(client: Client) -> dict[str, Any]:
    content = (
        "【DEMO / 取消与重复启动样例】\n"
        + "双方承担保密义务并由演示法院诉讼管辖，双方签字盖章后生效。\n" * 12_000
    ).encode("utf-8")
    uploaded = client.multipart(
        "/api/documents/upload",
        "user",
        "cancel-demo.txt",
        content,
        {"docType": "CONTRACT"},
    )
    started = threading.Event()
    holder: dict[str, str] = {}

    def on_event(name: str, payload: dict[str, Any]) -> None:
        if name == "start":
            holder["reviewKey"] = str(payload.get("reviewId") or payload.get("auditId") or "")
            started.set()

    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(client.stream_review, int(uploaded["id"]), "user", on_event)
        require(started.wait(20), "取消场景未收到 SSE start")
        review_key = holder["reviewKey"]
        expect_status(
            lambda: client.stream_review(int(uploaded["id"]), "user"),
            409,
            "同文档重复启动",
        )
        client.json("POST", f"/api/reviews/{urllib.parse.quote(review_key)}/cancel", "user")
        result = future.result(timeout=60)
    detail = client.json("GET", f"/api/reviews/{urllib.parse.quote(review_key)}", "user")
    require(detail["review"]["status"] == "CANCELLED", "取消后审核未进入 CANCELLED")
    require(any(item["event"] == "cancelled" for item in result["events"]), "SSE 未返回 cancelled 事件")
    return {"documentId": uploaded["id"], "reviewKey": review_key, "status": detail["review"]["status"]}


def close_loop(
    client: Client,
    case_result: dict[str, Any],
    evidence_dir: Path,
) -> dict[str, Any]:
    review_key = case_result["reviewKey"]
    detail = client.json("GET", f"/api/reviews/{urllib.parse.quote(review_key)}", "reviewer")
    require(detail["findings"], "整改闭环需要至少一个真实风险项")
    for item in detail["findings"]:
        finding = item["finding"]
        if finding["status"] == "OPEN":
            client.json(
                "POST",
                f"/api/findings/{urllib.parse.quote(finding['findingKey'])}/review",
                "reviewer",
                {"decision": "CONFIRM", "comment": "自动验收：确认命中原文与规则，需要整改。"},
            )
    detail = client.json("GET", f"/api/reviews/{urllib.parse.quote(review_key)}", "compliance")
    finding = detail["findings"][0]["finding"]
    task = client.json(
        "POST",
        "/api/remediations",
        "compliance",
        {
            "findingKey": finding["findingKey"],
            "assigneeId": "user@demo.local",
            "dueDate": (dt.date.today() + dt.timedelta(days=7)).isoformat(),
            "description": "自动验收：删除无限责任表述，并补充对等责任上限。",
        },
    )
    task_key = task["taskKey"]
    client.json("POST", f"/api/remediations/{urllib.parse.quote(task_key)}/start", "user")
    client.json(
        "POST",
        f"/api/remediations/{urllib.parse.quote(task_key)}/evidence",
        "user",
        {"evidenceText": "自动验收脱敏证据：修订稿 v3 已将责任上限调整为合同金额。"},
    )
    client.json(
        "POST",
        f"/api/remediations/{urllib.parse.quote(task_key)}/review",
        "reviewer",
        {"approved": True, "comment": "自动验收：证据与整改要求一致，复审通过。"},
    )
    closed = client.json("POST", f"/api/remediations/{urllib.parse.quote(task_key)}/close", "compliance")
    require(closed["status"] == "CLOSED", "整改任务未关闭")

    report = client.json("POST", "/api/reports", "reviewer", {"reviewKey": review_key}, timeout=60)
    report_again = client.json("POST", "/api/reports", "reviewer", {"reviewKey": review_key}, timeout=60)
    require(report_again["reportKey"] == report["reportKey"], "相同快照报告生成不幂等")
    _, headers, content = client.raw(
        "GET",
        f"/api/reports/{urllib.parse.quote(report['reportKey'])}/download",
        role="reviewer",
        accept="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        timeout=60,
    )
    require(content[:2] == b"PK" and len(content) > 1_000, "下载内容不是有效 DOCX 容器")
    require(headers.get("X-Content-SHA256") == report["sha256"], "报告响应哈希与元数据不一致")
    report_path = evidence_dir / "acceptance-report.docx"
    report_path.write_bytes(content)

    approved = client.json(
        "POST",
        f"/api/reviews/{urllib.parse.quote(review_key)}/approve",
        "reviewer",
        {"comment": "自动验收：全部确认风险已完成整改闭环。"},
    )
    require(approved["status"] == "APPROVED", "审核未进入 APPROVED")
    chain = client.json("GET", "/api/audit/verify", "compliance")
    require(chain["valid"], f"审计哈希链断裂：{chain}")
    return {
        "reviewKey": review_key,
        "taskKey": task_key,
        "taskStatus": closed["status"],
        "reviewStatus": approved["status"],
        "report": report,
        "reportPath": str(report_path.relative_to(ROOT)),
        "auditVerification": chain,
    }


def run(base_url: str, evidence_dir: Path) -> dict[str, Any]:
    evidence_dir.mkdir(parents=True, exist_ok=True)
    client = Client(base_url)
    health = client.json("GET", "/api/health")
    require(health["status"] == "UP", "后端健康检查未通过")
    require(health["llmProvider"] == "mock" and health["llmReady"], "零密钥 Mock 模式未就绪")
    require(health["ruleCount"] >= 10, "规则包未完整加载")

    identity = client.json("GET", "/api/auth/me", "user")
    require(identity["tenantId"] == "tenant-a" and identity["role"] == "USER", "演示身份不正确")
    bad_token = base64.b64encode(b"user@demo.local:wrong-password").decode("ascii")
    expect_status(
        lambda: _wrong_password_request(client, bad_token),
        401,
        "错误密码",
    )

    manifest = json.loads((SAMPLES / "expected-results.json").read_text(encoding="utf-8"))
    cases = {item["file"]: item for item in manifest["cases"]}
    results: list[dict[str, Any]] = []

    safe_path = SAMPLES / "contract-v1-safe.txt"
    safe = client.multipart(
        "/api/documents/upload", "user", safe_path.name, safe_path.read_bytes(), {"docType": "CONTRACT"})
    duplicate = client.multipart(
        "/api/documents/upload", "user", safe_path.name, safe_path.read_bytes(), {"docType": "CONTRACT"})
    require(duplicate["duplicate"] and duplicate["id"] == safe["id"], "重复上传未返回原文档")
    safe_stream = client.stream_review(int(safe["id"]))
    results.append(evaluate_case(client, cases[safe_path.name], int(safe["id"]), safe_stream))

    risky_path = SAMPLES / "contract-v2-risky.txt"
    risky = client.multipart(
        f"/api/documents/{safe['id']}/versions", "user", risky_path.name, risky_path.read_bytes())
    risky_stream = client.stream_review(int(risky["id"]))
    risky_result = evaluate_case(client, cases[risky_path.name], int(risky["id"]), risky_stream)
    results.append(risky_result)

    for filename in ["privacy-demo.md", "policy-demo.md", "prompt-injection-demo.txt"]:
        case = cases[filename]
        path = SAMPLES / filename
        uploaded = client.multipart(
            "/api/documents/upload", "user", filename, path.read_bytes(), {"docType": case["docType"]})
        stream = client.stream_review(int(uploaded["id"]))
        results.append(evaluate_case(client, case, int(uploaded["id"]), stream))

    expected_total = sum(len(item["expectedRuleCodes"]) for item in results)
    actual_total = sum(len(item["actualRuleCodes"]) for item in results)
    true_positive = sum(
        len(set(item["expectedRuleCodes"]) & set(item["actualRuleCodes"]))
        for item in results
    )
    false_positive = actual_total - true_positive
    false_negative = expected_total - true_positive
    precision = true_positive / actual_total if actual_total else 1.0
    recall = true_positive / expected_total if expected_total else 1.0
    require(false_positive == 0 and false_negative == 0, "样例规则评估存在误报或漏报")

    zero_hit = client.json(
        "GET",
        "/api/regulations/search?query=%E7%81%AB%E6%98%9F%E9%87%8F%E5%AD%90%E9%80%9A%E9%81%93&scope=CONTRACT",
        "user",
    )
    require(zero_hit == [], "法规零命中被填充了无关条目")
    expect_status(
        lambda: client.json("GET", f"/api/documents/{risky['id']}", "tenant_b"),
        403,
        "跨租户文档读取",
    )
    expect_status(
        lambda: client.json("GET", "/api/audit/events", "user"),
        403,
        "普通用户读取审计链",
    )

    cancellation = exercise_cancellation(client)
    closed_loop = close_loop(client, risky_result, evidence_dir)
    return {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "baseUrl": base_url,
        "mode": "zero-key-mock",
        "health": health,
        "ruleEvaluation": {
            "caseCount": len(results),
            "truePositive": true_positive,
            "falsePositive": false_positive,
            "falseNegative": false_negative,
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "scope": "内置规则包 + 仓库内 DEMO 法规集；不是法律准确率",
        },
        "cases": results,
        "negativeChecks": {
            "duplicateUpload": True,
            "wrongPassword": "401",
            "crossTenantRead": "403",
            "userAuditRead": "403",
            "regulationZeroHit": True,
        },
        "cancellation": cancellation,
        "closedLoop": closed_loop,
    }


def _wrong_password_request(client: Client, token: str) -> None:
    request = urllib.request.Request(
        client.base + "/api/auth/me",
        headers={"Authorization": f"Basic {token}", "Accept": "application/json"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=10):
            return
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception:
            payload = None
        raise ApiFailure(error.code, (payload or {}).get("message", error.reason), payload) from error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("BASE_URL", "http://127.0.0.1:19071"))
    parser.add_argument("--evidence-dir", type=Path, default=DEFAULT_EVIDENCE)
    args = parser.parse_args()
    evidence_dir = args.evidence_dir.resolve()
    started = time.perf_counter()
    try:
        result = run(args.base, evidence_dir)
        result["elapsedSeconds"] = round(time.perf_counter() - started, 3)
        output = evidence_dir / "acceptance-latest.json"
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({
            "status": "PASS",
            "evidence": str(output),
            "report": result["closedLoop"]["reportPath"],
            "elapsedSeconds": result["elapsedSeconds"],
            "ruleEvaluation": result["ruleEvaluation"],
        }, ensure_ascii=False))
        return 0
    except Exception as error:
        failure = {
            "status": "FAIL",
            "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "baseUrl": args.base,
            "elapsedSeconds": round(time.perf_counter() - started, 3),
            "errorType": type(error).__name__,
            "message": str(error),
        }
        evidence_dir.mkdir(parents=True, exist_ok=True)
        (evidence_dir / "acceptance-failure.json").write_text(
            json.dumps(failure, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(failure, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
