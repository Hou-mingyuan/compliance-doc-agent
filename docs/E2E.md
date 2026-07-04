# E2E 端到端验收指南（Mock 模式）

> 本文档描述 **零 API Key** 条件下，从环境启动 → 上传样例 → SSE 流式审核 → 报告验收的完整端到端流程。  
> 适用版本：`compliance-doc-agent` MVP（`LLM_PROVIDER=mock`）。

---

## 1. 验收范围

| 阶段 | 验收点 |
| --- | --- |
| 环境 | Mock 模式 `docker compose up` 或本地等价启动，健康检查返回 `llmProvider: mock` |
| 样例 | 使用内置样例文档，规则引擎命中 ≥ 2 条 |
| 审核 | SSE 流式输出 `start` → `finding*` → `token*` → `summary` → `done` |
| 报告 | 前端或 API 可查看风险项、摘要与详细分析 |

完整验收标准对照见 [project-07-spec.md §14](./ai-portfolio/project-07-spec.md#14-验收标准)。

---

## 2. 前置条件

| 项 | 要求 |
| --- | --- |
| Docker 方式 | Docker Desktop + Compose v2；`backend/Dockerfile` 已就绪 |
| 本地方式 | JDK 17+、Maven 3.8+、Node.js 18+ |
| 网络 | Mock 模式**无需**外网大模型密钥 |
| 端口 | Docker：后端 `8080`、前端 `5173`；本地开发：后端 `8090`、前端 `5173` |

内置样例文件（仓库内）：

| 文件 | 路径 | 用途 |
| --- | --- | --- |
| 合同条款片段 | `backend/src/main/resources/samples/合同条款片段.txt` | 合同类规则命中（PII、不公平责任、失效条款等） |
| 采购管理制度 | `backend/src/main/resources/samples/采购管理制度.md` | 制度类规则命中 |
| 内控检查清单 | `backend/src/main/resources/samples/内控检查清单.md` | 内控场景 |

---

## 3. 方式 A：Docker Compose 一键启动（推荐验收路径）

### 3.1 准备环境变量

```bash
cd compliance-doc-agent
cp .env.example .env
```

确认 `.env` 中 Mock 配置（默认值即可）：

```env
LLM_PROVIDER=mock
LLM_API_KEY=
BACKEND_PORT=8080
FRONTEND_PORT=5173
```

### 3.2 启动服务

```bash
docker compose up -d --build
```

等待 `backend` 健康检查通过（`wget http://127.0.0.1:8080/api/health`）。

### 3.3 验证健康检查

```bash
curl -s http://localhost:8080/api/health
```

**通过条件**：

```json
{
  "code": 0,
  "data": {
    "status": "UP",
    "llmProvider": "mock"
  }
}
```

### 3.4 访问前端

浏览器打开：**http://localhost:5173**

> **端口说明**：Compose 将后端映射到宿主机 `8080`；前端 Vite dev 映射到 `5173`。若前端代理目标与后端端口不一致，请检查 `frontend/vite.config.ts` 中 `/api` 代理是否指向 `http://backend:8080`（容器内）或 `http://localhost:8080`（宿主机联调）。

---

## 4. 方式 B：本地开发启动（当前最稳定）

当 Docker 镜像尚未就绪时，可用本地方式完成同等验收。

### 4.1 启动后端（Mock）

```bash
cd compliance-doc-agent/backend
mvn spring-boot:run
```

### 4.2 启动前端

```bash
cd compliance-doc-agent/frontend
npm install
npm run dev
```

确认 `frontend/vite.config.ts` 代理指向后端实际端口（默认 `8090`）：

```ts
proxy: {
  "/api": { target: "http://localhost:8090", changeOrigin: true },
}
```

### 4.3 健康检查

```bash
curl -s http://localhost:8090/api/health
```

---

## 5. 上传样例文档

### 5.1 UI 路径（目标流程）

1. 打开 http://localhost:5173 ，进入 **「上传合规文档」** 页。
2. 拖拽或选择样例文件，例如 `backend/src/main/resources/samples/合同条款片段.txt`。
3. 上传成功后，文档列表出现新记录，状态为 **已上传**。
4. 点击 **「开始审核」**，跳转至报告页（携带 `documentId`）。

> **接口约定**：`POST /api/documents/upload`（multipart）、`GET /api/documents`。若上传接口尚未落地，请使用下方 API 路径先行验收。

### 5.2 API 路径（当前可用 · 文本入库）

将样例内容通过分析接口写入数据库，获得 `documentId` 供 SSE 使用：

**PowerShell（Windows）：**

```powershell
$sample = Get-Content -Raw -Encoding UTF8 "backend/src/main/resources/samples/合同条款片段.txt"
$body = @{
  title   = "合同条款片段-Mock样例"
  docType = "CONTRACT"
  content = $sample
} | ConvertTo-Json -Depth 3
Invoke-RestMethod -Method POST -Uri "http://localhost:8090/api/compliance/analyze" `
  -ContentType "application/json; charset=utf-8" -Body $body
```

**bash（Linux / macOS）：**

```bash
curl -s -X POST http://localhost:8090/api/compliance/analyze \
  -H "Content-Type: application/json; charset=utf-8" \
  -d "$(jq -n \
    --arg title '合同条款片段-Mock样例' \
    --arg content "$(cat backend/src/main/resources/samples/合同条款片段.txt)" \
    '{title: $title, docType: "CONTRACT", content: $content}')"
```

**通过条件**：

- 响应 `code` 为 `0`
- `data.documentId` 为有效数字（记下此 ID，例如 `1`）
- `data.llmProvider` 为 `"mock"`
- `data.ruleHits` 数组长度 ≥ 2

**合同样例预期命中（至少包含）**：

| 规则 ID | 名称 | 触发原因 |
| --- | --- | --- |
| `R-CON-003` | 禁止无限连带责任表述 | 第三条「免除全部责任」 |
| `R-PII-001` | 疑似身份证号泄露 | 第五条身份证号 |
| `R-DATE-001` | 合同日期逻辑异常 | 第六条「已失效」 |

---

## 6. SSE 流式审核

### 6.1 命令行验收

将 `{documentId}` 替换为上一步返回的 ID：

```bash
curl -N -X POST "http://localhost:8090/api/compliance/audit/stream/1" \
  -H "Accept: text/event-stream"
```

Docker 环境将端口改为 `8080`：

```bash
curl -N -X POST "http://localhost:8080/api/compliance/audit/stream/1" \
  -H "Accept: text/event-stream"
```

### 6.2 SSE 事件序列

后端接口：`POST /api/compliance/audit/stream/{docId}`

| 顺序 | 事件名 | 含义 | 示例 data 字段 |
| --- | --- | --- | --- |
| 1 | `start` | 审核开始 | `auditId`, `documentId` |
| 2 | `finding` | 规则命中（可多次） | `severity`, `rule`, `description`, `location` |
| 3 | `token` | Mock LLM 流式 token（可多次） | `text` |
| 4 | `summary` | 审核摘要 | `text` |
| 5 | `done` | 审核结束 | `auditId`, `summary` |
| — | `error` | 失败（异常时） | `message` |

**通过条件**：

1. 首条事件为 `event: start`
2. 至少收到 2 条 `event: finding`
3. 收到若干 `event: token`（Mock LLM 流式输出）
4. 收到 `event: summary` 且 `text` 含风险统计
5. 末条为 `event: done`，无 `error`

**示例片段**：

```
event: start
data: {"auditId":"a1b2c3...","documentId":"1"}

event: finding
data: {"severity":"high","rule":"禁止无限连带责任表述","description":"...","location":"R-CON-003"}

event: token
data: {"text":"【Mock 合规分析摘要】"}

event: summary
data: {"text":"共发现 4 项风险（最高等级：ERROR）。"}

event: done
data: {"auditId":"a1b2c3...","summary":"共发现 4 项风险（最高等级：ERROR）。"}
```

### 6.3 浏览器 UI 验收

1. 从上传页点击 **「开始审核」**（或访问 `/report?documentId={id}&filename=...`）。
2. 报告页顶部出现 **「AI 正在流式生成审核报告…」** 进度条。
3. **风险项** 列表随 `finding` 事件实时追加，按严重等级着色。
4. **详细分析** 区域随 `token` 事件逐字追加，末尾显示光标 `▌`。
5. 审核结束后展示 **审核摘要**、**风险分布** 芯片，进度条消失。
6. 可点击 **「重新审核」** 对同一文档再次发起 SSE。

**通过条件**：

- 无红色错误横幅
- 风险项数量与 SSE `finding` 次数一致
- 摘要非空，且与 `summary` 事件内容一致

---

## 7. 报告验收清单

按顺序勾选，全部通过即 E2E 验收合格：

| # | 步骤 | 操作 | 通过标准 |
| --- | --- | --- | --- |
| 1 | 环境启动 | `docker compose up -d --build` 或本地 `mvn spring-boot:run` + `npm run dev` | 前后端均可访问，无启动报错 |
| 2 | Mock 确认 | `GET /api/health` | `llmProvider` = `"mock"` |
| 3 | 样例入库 | 上传 `合同条款片段.txt` 或调用 `/api/compliance/analyze` | 返回 `documentId`，`ruleHits` ≥ 2 |
| 4 | SSE 启动 | `POST /api/compliance/audit/stream/{documentId}` | 收到 `start` 事件 |
| 5 | 规则流式 | 观察 `finding` 事件 | ≥ 2 条，含 `severity` / `rule` / `description` |
| 6 | LLM 流式 | 观察 `token` 事件 | Mock 摘要逐 token 输出 |
| 7 | 摘要完成 | 观察 `summary` + `done` | 摘要含风险统计，流正常结束 |
| 8 | UI 报告 | 浏览器报告页 | 风险项、摘要、详细分析三块内容完整展示 |
| 9 | 数据落库 | （可选）查 H2 `compliance_check` 表 | 规则命中记录与 `finding` 一致 |
| 10 | 单测 | `cd backend && mvn test` | 规则引擎等测试通过 |

---

## 8. 自动化脚本（可选）

将以下脚本保存为 `scripts/e2e-mock.sh`，在仓库根目录执行：

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE_URL:-http://localhost:8090}"
SAMPLE="backend/src/main/resources/samples/合同条款片段.txt"

echo "==> 1. Health"
curl -sf "$BASE/api/health" | grep -q '"llmProvider":"mock"'

echo "==> 2. Analyze (upload equivalent)"
RESP=$(curl -sf -X POST "$BASE/api/compliance/analyze" \
  -H "Content-Type: application/json; charset=utf-8" \
  -d "$(jq -n --arg c "$(cat "$SAMPLE")" '{title:"E2E",docType:"CONTRACT",content:$c}')")
DOC_ID=$(echo "$RESP" | jq -r '.data.documentId')
test "$DOC_ID" != "null" && test "$DOC_ID" -gt 0

echo "==> 3. SSE audit (documentId=$DOC_ID)"
STREAM=$(curl -sN -X POST "$BASE/api/compliance/audit/stream/$DOC_ID" -H "Accept: text/event-stream")
echo "$STREAM" | grep -q 'event: start'
echo "$STREAM" | grep -c 'event: finding' | awk '{exit !($1>=2)}'
echo "$STREAM" | grep -q 'event: summary'
echo "$STREAM" | grep -q 'event: done'

echo "E2E Mock 验收通过 (documentId=$DOC_ID)"
```

---

## 9. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 前端上传报网络错误 | 后端未启动或代理端口不一致 | 核对 `vite.config.ts` 与 `application.yml` 端口 |
| SSE 返回「文档不存在」 | `documentId` 无效或 H2 内存库已重启 | 重新执行 analyze / 上传 |
| SSE 无 `finding` | 样例内容过短或未含违规点 | 换用 `合同条款片段.txt` |
| Docker build 失败 | `backend/Dockerfile` 缺失 | 先用方式 B 本地验收，或补齐 Dockerfile |
| `llmProvider` 非 mock | 环境变量被覆盖 | 确认 `LLM_PROVIDER=mock` 且 `LLM_API_KEY` 为空 |

---

## 10. 相关文档

| 文档 | 说明 |
| --- | --- |
| [USAGE.md](./USAGE.md) | Mock 零密钥使用说明 |
| [project-07-spec.md](./ai-portfolio/project-07-spec.md) | 完整规格与验收标准 |
| [../frontend/src/api.ts](../frontend/src/api.ts) | 前端 REST + SSE 约定 |
| [../docker-compose.yml](../docker-compose.yml) | Compose 服务与端口 |

---

*最后更新：E2E 验收文档初版，覆盖 Mock 模式 docker compose / 本地双路径、样例上传、SSE 审核与报告验收清单。*
