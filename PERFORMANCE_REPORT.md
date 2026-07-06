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

## 后续优化

- 对 `POST /api/compliance/audit/stream/{docId}` 增加独立 soak 测试（含 Mock LLM 流式）。
- 文档上传端点增加并发上传与 5MB 边界压测。
- 生产环境在网关层增加分布式限流与 OpenTelemetry 指标。
- 接入真实 LLM 后单独评估 token 延迟与超时配置（`LLM_TIMEOUT`）。
