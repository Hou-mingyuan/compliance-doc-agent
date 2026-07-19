# compliance-doc-agent 性能报告

报告日期：2026-07-06

## 目标

- 健康检查与文档列表等只读 API 在 10–50 并发下 P95 `< 800ms`、错误率 `< 1%`。
- SSE 流式审核（`/api/compliance/audit/stream/{docId}`）与 Mock LLM 叙事输出单独评估，不纳入本 smoke 阈值。

## 压测脚本

脚本：`performance/k6-smoke.js`

覆盖端点：

| 端点 | 说明 |
| --- | --- |
| `GET /api/health` | 健康检查（含 `llmProvider`） |
| `GET /api/documents` | 已上传文档列表 |

运行示例（需后端已启动，默认 Docker 后端端口 8080）：

```bash
docker run --rm ^
  -e BASE_URL=http://host.docker.internal:8080 ^
  -e VUS=20 ^
  -e DURATION=1m ^
  -v D:/project-hub/compliance-doc-agent/performance:/scripts ^
  grafana/k6:latest run /scripts/k6-smoke.js
```

本地 Maven 后端（8090）：

```bash
docker run --rm ^
  -e BASE_URL=http://host.docker.internal:8090 ^
  -e VUS=10 ^
  -e DURATION=30s ^
  -v D:/project-hub/compliance-doc-agent/performance:/scripts ^
  grafana/k6:latest run /scripts/k6-smoke.js
```

## 当前状态

- 后端 JUnit 覆盖文档上传、解析分块、规则引擎命中与 SSE 编排流。
- 前端 Vite 生产构建可用（`npm run build`）。
- Docker Compose 默认 Mock LLM，零密钥可完整演示审核链路。
- 已提供 k6 smoke 脚本，覆盖只读 API 基线。

## 实测结果（Docker Desktop · 2026-07-06）

环境：`compliance-doc-agent-backend` @ `:8080`，k6 `host.docker.internal`，`VUS=20`，`DURATION=1m`，`THINK_TIME_SECONDS=1`。

| 并发 VU | 时长 | 请求数 | 吞吐 (req/s) | P95 (fast) | 错误率 | 结论 |
| ---: | --- | ---: | ---: | ---: | ---: | --- |
| 20 | 1m | 2262 | 37.1 | **118.09 ms** | **0.00%** | 达标（<800ms） |
| 20 | 30s (Round-6) | 1160 | — | **55.94 ms** | **0.00%** | project-hub-2 复跑 · 580 iter |

checks 3393/3393 通过：`health ok`、`mock provider`、`documents ok`。

## SSE audit Mock soak（2026-07-06）

脚本：`scripts/sse-audit-soak.py` — 对 `POST /api/compliance/audit/stream/{docId}` 在 Mock LLM 下循环压测。

```bash
# 快速验证（60s）
python scripts/sse-audit-soak.py --base http://127.0.0.1:8080 --duration 60

# Round-5 长稳 soak（30 min）
python scripts/sse-audit-soak.py --base http://127.0.0.1:8080 --duration 1800
```

**通过条件**：每轮 `start` ≥1 · `finding` ≥2 · `done` ≥1 · 无 `error` 事件；stdout 末尾 `SOAK_SUMMARY` JSON `allOk=true`。

| 指标 | 说明 |
| --- | --- |
| `ttfbMs` | 首条 SSE 事件到达时间 |
| `elapsedMs` | 单轮流结束耗时 |
| `runs` | `--duration` 内完成的审核轮数 |
| `p95ElapsedMs` / `p95TtfbMs` | Round-5 新增分位统计 |
| `maxElapsedMs` | 单轮最大耗时 |

### 60s 快速验证（2026-07-06 · project-hub-1）

| duration | runs | allOk | avg TTFB | avg elapsed | p95 elapsed | p95 TTFB | max elapsed | errorRuns |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 60s | **104** | **true** | 77.0 ms | 77.5 ms | 207.8 ms | 206.9 ms | 339.7 ms | 0 |
| **1800s** | **3140** | **true** | 68.9 ms | 69.6 ms | 176.7 ms | 174.1 ms | 2038.5 ms | 0 |

### 30 min 长稳 soak（2026-07-06 · Round-5）

完整日志：`docs/evidence/sse-soak-30min-20260706.log`

```bash
python scripts/sse-audit-soak.py --base http://127.0.0.1:8080 --duration 1800
```

> 墙钟 1800s 循环压测 Mock SSE 审核流；结果以日志末尾 `SOAK_SUMMARY` 为准。

## 后续优化

- ~~对 `POST /api/compliance/audit/stream/{docId}` 增加独立 soak 测试~~ ✅ `sse-audit-soak.py`
- 文档上传端点增加并发上传与 5MB 边界压测。
- 生产环境在网关层增加分布式限流与 OpenTelemetry 指标。
- 接入真实 LLM 后单独评估 token 延迟与超时配置（`LLM_TIMEOUT`）。
