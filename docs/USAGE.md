# 使用指南 · Mock 零密钥体验（草稿）

> 本文档说明如何**不配置任何大模型 API Key**，体验合规文档智能审核 Agent 的核心流程。  
> 当前为 MVP 草稿：后端已支持文本分析与 Mock LLM；前端已具备上传页 / 报告页 / SSE 流式展示骨架，完整文件上传与 `/api/audit/stream` 接口待后端补齐后与本指南同步更新。

---

## 为什么可以零密钥运行？

项目默认 `LLM_PROVIDER=mock`（见 `backend/src/main/resources/application.yml`）。Mock 模型为离线内置、规则驱动，无需联网调用外部大模型，即可跑通：

- 内置规则引擎扫描（关键词 / 正则）
- Mock LLM 语义复核与摘要生成
- 风险项持久化与报告返回

适合本地体验、演示与 CI。接入 OpenAI / DeepSeek / 通义 / Ollama 等真实模型时，只需在环境变量中填入 `LLM_API_KEY` 并将 `LLM_PROVIDER` 设为 `openai`（OpenAI 兼容网关）。

---

## 前置条件

| 方式 | 要求 |
| --- | --- |
| **后端本地** | JDK 17+、Maven 3.8+ |
| **前端本地** | Node.js 18+ |
| **Docker（规划中）** | Docker Desktop + Compose |

默认端口：

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| 后端 | **8090** | `application.yml` 中 `server.port` |
| 前端 dev | **5173** | Vite 开发服务器 |
| 前端 proxy | → 8080 | 当前 `vite.config.ts` 代理目标；**本地联调时请改为 8090 或统一 docker-compose 端口** |

---

## 方式一：后端 Mock 模式（当前可用）

### 1. 启动后端

```bash
cd compliance-doc-agent/backend
mvn spring-boot:run
```

无需设置任何环境变量。启动后验证：

```bash
curl http://localhost:8090/api/health
```

预期返回（`code: 0`）：

```json
{
  "code": 0,
  "data": { "status": "UP", "llmProvider": "mock" }
}
```

### 2. 发起文本合规分析

当前 MVP 接口为 **POST `/api/compliance/analyze`**（JSON 正文，非文件上传）：

```bash
curl -X POST http://localhost:8090/api/compliance/analyze \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"采购合同-示例\",
    \"docType\": \"CONTRACT\",
    \"content\": \"本合同约定付款账期为120天。甲方享有单方无条件解除权。未约定保密条款。\"
  }"
```

预期行为：

1. 规则引擎命中内置规则（如付款周期超 90 天、缺失保密条款、单方解除权等）
2. Mock LLM 生成合规摘要与整改建议
3. 返回 `documentId`、风险等级、规则命中列表与 LLM 摘要

---

## 方式二：前端 + 后端联调（目标流程）

### 1. 启动后端

```bash
cd compliance-doc-agent/backend
mvn spring-boot:run
```

### 2. 调整前端代理（联调必做）

编辑 `frontend/vite.config.ts`，将 proxy target 改为后端实际端口：

```ts
proxy: {
  "/api": { target: "http://localhost:8090", changeOrigin: true },
}
```

### 3. 启动前端

```bash
cd compliance-doc-agent/frontend
npm install
npm run dev
```

浏览器访问：http://localhost:5173

### 4. 体验路径：上传 → 审核 → 报告

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 打开 **「文档上传」** | 拖拽或选择 PDF / DOCX / TXT 等合规材料 |
| 2 | 上传成功后点击 **「开始审核」** | 跳转至审核报告页，携带 `documentId` |
| 3 | 报告页自动发起 **SSE 流式审核** | 实时展示风险项（finding）、详细分析（token 流）、审核摘要 |
| 4 | 审核完成 | 可查看报告 ID，或通过 `/report/:id` 加载历史报告 |
| 5 | 点击 **「重新审核」** | 对同一文档再次发起流式审核 |

> **接口约定（前端已实现，后端待对齐）**  
> - `POST /api/documents/upload` — multipart 上传  
> - `GET /api/documents` — 文档列表  
> - `POST /api/audit/stream` — SSE 流式审核（事件：`start` / `finding` / `token` / `summary` / `done` / `error`）  
> - `GET /api/audit/{id}` — 历史报告详情  

---

## 方式三：Docker 一键启动（规划中）

```bash
cd compliance-doc-agent
cp .env.example .env    # 可选填 LLM_API_KEY
docker compose up -d --build
```

Compose 将统一前后端端口映射；实现完成后本段将补充具体 URL。

---

## 环境变量说明

所有变量通过 `.env` 或系统环境变量注入，**密钥不入库**。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LLM_PROVIDER` | `mock` | `mock` = 离线内置；`openai` = OpenAI 兼容 HTTP 网关 |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | 兼容网关地址 |
| `LLM_MODEL` | `gpt-4o-mini` | 模型名称 |
| `LLM_API_KEY` | （空） | 留空时自动使用 Mock |
| `LLM_TEMPERATURE` | `0.2` | 采样温度，合规场景建议低温度 |
| `LLM_TIMEOUT` | `90` | LLM 请求超时（秒） |

### 常用配置示例

**离线 / 演示（默认，零密钥）**

```bash
# 无需设置，或显式：
LLM_PROVIDER=mock
```

**DeepSeek**

```bash
LLM_PROVIDER=openai
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-chat
LLM_API_KEY=sk-your-key
```

**本地 Ollama**

```bash
LLM_PROVIDER=openai
LLM_BASE_URL=http://localhost:11434/v1
LLM_MODEL=qwen2.5:7b
LLM_API_KEY=ollama
```

**通义千问**

```bash
LLM_PROVIDER=openai
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen-plus
LLM_API_KEY=sk-your-dashscope-key
```

---

## Mock 模式示例话术

对 **POST `/api/compliance/analyze`** 使用以下示例正文，可稳定触发多条内置规则：

| 场景 | 示例内容片段 | 预期命中 |
| --- | --- | --- |
| 付款账期过长 | `付款账期为120天` | `PAYMENT_TERM_EXCEEDS_90D` |
| 缺失保密 | 全文无「保密」相关表述 | `MISSING_NDA_CLAUSE` |
| 单方解除 | `甲方享有单方无条件解除权` | `UNILATERAL_TERMINATION` |
| 违约金过高 | `违约金为合同总额的30%` | `EXCESSIVE_PENALTY` |

---

## 常见问题

**Q：前端上传后报网络错误？**  
A：确认后端已启动，且 `vite.config.ts` 的 proxy target 与后端端口（8090）一致。

**Q：没有 LLM Key 能完整体验吗？**  
A：可以。Mock 模式下规则引擎 + Mock LLM 摘要均可离线运行；仅 SSE 流式与文件上传需等待后端接口对齐。

**Q：如何切换真实大模型？**  
A：设置 `LLM_PROVIDER=openai` 并填入 `LLM_API_KEY`，重启后端即可。

**Q：支持哪些文档格式？**  
A：目标支持 PDF / DOCX / TXT / MD（见 [project-07-spec.md](./ai-portfolio/project-07-spec.md)）；当前 analyze 接口接受纯文本 JSON。

---

## 相关文档

| 文档 | 说明 |
| --- | --- |
| [project-07-spec.md](./ai-portfolio/project-07-spec.md) | MVP 规格书（API、数据模型、验收标准） |
| [../frontend/src/api.ts](../frontend/src/api.ts) | 前端 REST + SSE 接口约定 |
| [../backend/src/main/resources/application.yml](../backend/src/main/resources/application.yml) | 后端默认配置 |

---

*本文为草稿，随后端 upload / SSE 接口落地后将更新 Docker 章节与完整端到端截图说明。*
