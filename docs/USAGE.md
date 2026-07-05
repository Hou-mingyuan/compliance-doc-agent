# 使用指南 · Mock 零密钥体验

本文档说明如何**不配置任何大模型 API Key**，完整体验合规文档智能审核 Agent：**上传文档 → SSE 流式审核 → 查看报告**。

---

## 为什么可以零密钥运行？

项目默认 `LLM_PROVIDER=mock`（见 `backend/src/main/resources/application.yml`）。Mock 模型为离线内置，无需联网调用外部大模型，即可跑通：

- 内置 JSON 规则引擎（10 条默认规则，关键词 / 正则）
- Mock LLM 语义摘要（流式 token 输出）
- 风险项持久化与审核摘要

适合本地体验、演示与 CI。接入 OpenAI / DeepSeek / 通义 / Ollama 等真实模型时，在环境变量中填入 `LLM_API_KEY` 并将 `LLM_PROVIDER` 设为 `openai` 即可。

---

## 前置条件

| 方式 | 要求 |
| --- | --- |
| **本地联调（推荐）** | JDK 17+、Maven 3.8+、Node.js 18+ |
| **Docker Compose** | Docker Desktop + Compose |

默认端口：

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| 后端 | **8090** | `application.yml` 中 `server.port` |
| 前端 dev | **5173** | Vite 开发服务器，proxy → 8090 |
| Docker 后端 | **8080** | `docker-compose.yml` 中 `BACKEND_HOST_PORT` |

---

## 方式一：前后端本地联调（已验证）

### 1. 启动后端（Mock，零密钥）

```bash
cd compliance-doc-agent/backend
mvn spring-boot:run
```

验证：

```bash
curl http://localhost:8090/api/health
```

预期：`"llmProvider":"mock"`

### 2. 启动前端

`frontend/vite.config.ts` 已将 `/api` 代理到 `http://localhost:8090`，无需修改。

```bash
cd compliance-doc-agent/frontend
npm install
npm run dev
```

浏览器打开：http://localhost:5173

### 3. 体验路径：上传 → SSE 审核 → 报告

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 打开 **「文档上传」**，上传 `backend/src/main/resources/samples/合同条款片段.txt` | 列表出现文档，状态「已上传」 |
| 2 | 点击 **「开始审核」** | 跳转报告页，自动发起 SSE |
| 3 | 观察流式输出 | 实时追加 **风险项**（finding）、**详细分析**（narrative token）、**审核摘要**（summary） |
| 4 | 审核完成 | 显示报告 ID 与风险分布；可点击「重新审核」 |

**样例合同预期命中（部分）：**

| 规则 ID | 说明 |
| --- | --- |
| R-CON-001 | 缺失争议解决条款 |
| R-CON-003 | 禁止无限连带责任 / 免除全部责任 |
| R-PII-001 | 疑似身份证号泄露 |
| R-POL-001 / R-DISC-001 | 制度/披露类缺失条款（按全文扫描） |

---

## 前端 SSE 联调说明

> 前端无独立 `AuditStream.vue`；SSE 消费逻辑分布在 `frontend/src/api.ts`（`streamAudit`）与 `frontend/src/views/ReportView.vue`（事件回调）。

### 调用链路

| 步骤 | 文件 | 行为 |
| --- | --- | --- |
| 1 | `UploadView.vue` | 点击「开始审核」→ `router.push({ path: '/report', query: { documentId, filename } })` |
| 2 | `ReportView.vue` | `onMounted` 检测 `documentId` 查询参数 → 调用 `startStream()` |
| 3 | `api.ts` → `streamAudit` | `POST /api/compliance/audit/stream/{documentId}`，`Accept: text/event-stream`，`AbortSignal` 支持停止 |
| 4 | `vite.config.ts` | `/api` 代理至 `http://localhost:8090`（Docker 外本地 dev 默认） |

### 前后端事件契约

后端 `ComplianceAuditController` 路径参数为 `docId`（`Long`）；前端以字符串 `documentId` 拼入 URL，二者一致。

| SSE `event` | 后端 payload | 前端处理（`ReportView.vue`） |
| --- | --- | --- |
| `start` | `{ auditId, documentId }` | 记录 `auditId` |
| `finding` | `{ severity, rule, description, location }` | 追加至 `findings[]`（按严重度排序展示） |
| `narrative` | `{ text }` | 追加至 `narrative` 流式正文（兼容旧名 `token`） |
| `summary` | `{ text }` | 显示审核摘要 |
| `done` | `{ auditId, summary }` | 结束流；若 `summary` 存在则覆盖摘要 |
| `error` | `{ message }` | 显示错误横幅 |

事件顺序：`start` → `finding*` → `narrative*` → `summary` → `done`（异常时 `error`）。

### 联调自检清单

1. 后端 `curl http://localhost:8090/api/health` 返回 `llmProvider: mock`
2. 前端 dev 控制台 Network 中 SSE 请求为 `POST /api/compliance/audit/stream/{id}`，状态 200，`Content-Type: text/event-stream`
3. 报告页依次出现：流式条 → 风险项列表追加 → 详细分析打字效果 → 审核摘要
4. 点击「停止」应触发 `AbortController.abort()`，流中断且无报错

---

## 方式二：命令行快速验证（无需前端）

### 1. 上传文档

```bash
curl -F "file=@backend/src/main/resources/samples/合同条款片段.txt" \
     -F "docType=CONTRACT" \
     http://localhost:8090/api/documents/upload
```

记录返回的 `data.id`（例如 `2`）。

### 2. SSE 流式审核

```bash
curl -N -X POST -H "Accept: text/event-stream" \
     http://localhost:8090/api/compliance/audit/stream/2
```

SSE 事件顺序：

```
start → finding* → narrative* → summary → done
```

| 事件 | 含义 |
| --- | --- |
| `start` | 审核开始，含 `auditId`、`documentId` |
| `finding` | 规则命中项：`severity` / `rule` / `description` / `location` |
| `narrative` | Mock LLM 流式分析片段（`text`） |
| `summary` | 审核摘要（如「共发现 N 项风险」） |
| `done` | 审核结束 |
| `error` | 失败原因 |

### 3. 文档列表

```bash
curl http://localhost:8090/api/documents
```

---

## 方式三：Docker Compose

```bash
cd compliance-doc-agent
cp .env.example .env    # 默认 Mock，无需填 LLM_API_KEY
docker compose up -d --build
```

| 入口 | 地址 |
| --- | --- |
| 后端健康检查 | http://localhost:8080/api/health |
| 前端（dev profile） | http://localhost:5173 |

> 使用 Docker 后端 + 容器外前端本地启动时，不要改源码；临时设置
> `VITE_PROXY_TARGET=http://localhost:8080` 后再运行 `npm run dev` 即可。

---

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LLM_PROVIDER` | `mock` | `mock` = 离线内置；`openai` = OpenAI 兼容网关 |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | 兼容网关地址 |
| `LLM_MODEL` | `gpt-4o-mini` | 模型名称 |
| `LLM_API_KEY` | （空） | 留空时使用 Mock |
| `LLM_TEMPERATURE` | `0.2` | 合规场景建议低温度 |

**DeepSeek 示例：**

```bash
LLM_PROVIDER=openai
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-chat
LLM_API_KEY=sk-your-key
```

---

## API 一览（MVP 联调）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查（含 `llmProvider`） |
| GET | `/api/documents` | 已上传文档列表 |
| POST | `/api/documents/upload` | multipart 上传（PDF / TXT / MD） |
| POST | `/api/compliance/audit/stream/{docId}` | SSE 流式审核 |
| POST | `/api/compliance/analyze` | JSON 文本分析（备用） |

---

## 常见问题

**Q：前端上传后报网络错误？**

A：确认后端已启动；本地开发时 proxy 指向 **8090**（`mvn spring-boot:run`）或 Docker **8080**。

**Q：没有 LLM Key 能完整体验吗？**

A：可以。规则引擎 + Mock LLM 摘要 + SSE 流式均已支持零密钥运行。

**Q：支持哪些格式？**

A：当前解析支持 **PDF / TXT / Markdown**（`.pdf` / `.txt` / `.md`），单文件 ≤ 5MB。

**Q：如何切换真实大模型？**

A：设置 `LLM_PROVIDER=openai` 并填入 `LLM_API_KEY`，重启后端。

---

## 相关文档

| 文档 | 说明 |
| --- | --- |
| [project-07-spec.md](./ai-portfolio/project-07-spec.md) | MVP 规格书 |
| [architecture.md](./architecture.md) | 系统架构 |
| [E2E.md](./E2E.md) | 端到端测试说明 |
| [../rules/default-rules.json](../rules/default-rules.json) | 内置 10 条合规规则 |

---

*最后验证：2026-07-05 · Mock 模式 · 上传 → SSE → 报告全链路通过*
