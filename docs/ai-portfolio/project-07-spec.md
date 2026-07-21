# Project 07 · 合规文档智能审核 Agent - 历史 MVP 规格书

> **历史资料：** 本文保存 0.1.0 初始设计，不代表当前实现、端口、API、角色或发布范围。当前发布候选契约以仓库根目录 README、`docs/architecture.md`、`docs/API.md` 和自动验收证据为准。

| 字段 | 内容 |
| --- | --- |
| 项目代号 | `compliance-doc-agent` |
| 作品集编号 | #7 |
| 版本 | MVP v0.1.0（规格） |
| 状态 | 待开发 |
| 路径 | `d:\project-hub\compliance-doc-agent\` |
| 更新日期 | 2026-07-04 |

---

## 1. 一句话定位

面向**内审 / 合规 / 法务**场景的智能文档审核系统：上传合同、制度、政策等文档 → 结合**可配置规则库 + LLM 语义理解**自动审查 → 输出结构化**风险报告**（风险等级、条款定位、整改建议、审计留痕）。

---

## 2. 背景与痛点

| 痛点 | 现状 | 本产品价值 |
| --- | --- | --- |
| 人工审阅耗时长 | 一份采购合同 20–50 页，法务逐条核对 | 自动扫描 + 高亮风险条款，人工聚焦例外 |
| 规则分散 | 合规要求散落在制度文件、监管条文、内部 checklist | 统一规则库，版本化管理 |
| 漏检与口径不一 | 不同审阅人标准不一 | 规则引擎保证底线，LLM 补充语义风险 |
| 审计追溯难 | 邮件/Word 批注难归档 | 每次审核生成报告 ID、时间线、操作人 |

---

## 3. MVP 目标与非目标

### 3.1 MVP 必做（In Scope）

1. **文档上传与管理**：支持 PDF / Word (.docx) / 纯文本；解析为可检索文本块（带页码/段落锚点）。
2. **规则库**：内置示例规则（如：缺失保密条款、付款周期超 90 天、单方解除权、竞业限制范围过大）；支持 CRUD 与启用/停用。
3. **混合审核引擎**：
   - **规则层**：关键词/正则/结构化条件（字段 + 运算符）→ 确定性命中；
   - **LLM 层**：对全文或命中上下文做语义合规判断（OpenAI 兼容，可 Mock）。
4. **风险报告**：汇总 findings（严重/高/中/低/提示），含条款摘录、页码定位、规则 ID、LLM 理由、整改建议。
5. **审核任务流**：上传 → 排队/执行 → 完成/失败；可重新审核。
6. **Web 界面**：文档列表、上传、规则管理、报告详情、简单看板（任务数/风险分布）。
7. **工程化**：Docker Compose 一键启动、`.env.example`、中文 README、基础单测（规则匹配 + 报告聚合）。

### 3.2 MVP 不做（Out of Scope）

- OCR 扫描件（图片 PDF）— Roadmap
- 多人协作批注、审批流 — Roadmap
- 与 OA/ERP 深度集成 — Roadmap
- 向量 RAG 跨文档比对 — 增强项
- 多租户 / RBAC — 增强项
- 电子签章、区块链存证 — 不做

---

## 4. 用户角色（MVP 简化）

| 角色 | 能力 |
| --- | --- |
| 合规审阅员 | 上传文档、发起审核、查看/导出报告 |
| 规则管理员 | 维护规则库（MVP 可与审阅员合并，无独立鉴权） |

> MVP 默认**单用户无登录**或固定 API Key；生产版再加 RBAC。

---

## 5. 核心用户流程

```mermaid
flowchart LR
  A[上传文档] --> B[解析文本块]
  B --> C[加载启用规则]
  C --> D[规则引擎扫描]
  D --> E[LLM 语义复核]
  E --> F[聚合 Findings]
  F --> G[生成风险报告]
  G --> H[前端展示 / 导出]
```

### 5.1 典型场景

**场景 A · 采购合同审核**

1. 上传 `采购合同-2024-XX.docx`
2. 系统命中规则：`PAYMENT_TERM_EXCEEDS_90D`（付款账期 > 90 天）
3. LLM 补充：「违约金比例 30% 显著高于行业惯例，建议协商降至 10% 以内」
4. 报告输出 2 条 High、1 条 Medium，附页码与建议

**场景 B · 内部制度合规自查**

1. 上传《员工手册 v3.pdf》
2. 规则检查：是否包含个人信息保护、反舞弊举报渠道等必备章节
3. LLM 检查表述是否与《个人信息保护法》原则冲突
4. 导出 PDF/Markdown 报告供内审归档

---

## 6. 技术栈（建议）

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 后端 | Java 17 + Spring Boot 3.3 | 与作品集 Java 项目风格一致 |
| 文档解析 | Apache POI (docx) + Apache PDFBox (pdf) | MVP 文本提取即可 |
| 规则引擎 | 自研轻量 DSL + SpEL/正则 | 可配置、可单测 |
| LLM | OpenAI 兼容 HTTP 客户端 + Mock 回退 | 与 chatbi / ai-service-agent 同模式 |
| 持久层 | MyBatis-Plus + H2（开发）/ MySQL（Docker） | 元数据 + 规则 + 报告 |
| 前端 | Vue 3 + Vite + Element Plus | 与作品集前端统一 |
| 部署 | docker-compose | backend + frontend + mysql（可选） |

---

## 7. 目录结构（目标）

```
compliance-doc-agent/
├── backend/
│   └── src/main/java/com/portfolio/compliance/
│       ├── document/      # 上传、解析、文本块
│       ├── rule/          # 规则 CRUD、规则引擎
│       ├── review/        # 审核编排、LLM 调用
│       ├── report/        # Findings 聚合、报告导出
│       ├── llm/           # OpenAI 兼容 + Mock
│       └── controller/
├── frontend/
│   └── src/views/         # Documents / Rules / Reports / Dashboard
├── sample-data/
│   └── rules/             # 内置示例规则 JSON
│   └── documents/         # 虚构合同样本（无敏感信息）
├── docs/
│   └── ai-portfolio/
│       └── project-07-spec.md   ← 本文件
├── docker-compose.yml
├── .env.example
├── VERSION
├── CHANGELOG.md
└── README.md
```

---

## 8. 数据模型（MVP）

### 8.1 实体

| 实体 | 关键字段 |
| --- | --- |
| `Document` | id, fileName, fileType, sha256, pageCount, status(UPLOADED/PARSED/FAILED), createdAt |
| `DocumentChunk` | id, documentId, pageNo, sectionTitle, content, charStart, charEnd |
| `ComplianceRule` | id, code, name, category, severity, ruleType(KEYWORD/REGEX/STRUCT), expression, enabled, description |
| `ReviewTask` | id, documentId, status(PENDING/RUNNING/DONE/FAILED), ruleSetVersion, llmProvider, startedAt, finishedAt |
| `Finding` | id, taskId, ruleId(nullable), severity, title, summary, evidence, pageNo, suggestion, source(RULE/LLM) |
| `Report` | id, taskId, documentId, riskScore, summaryJson, exportPath |

### 8.2 风险等级

| 等级 | 含义 | 示例 |
| --- | --- | --- |
| CRITICAL | 可能违法或重大合规漏洞 | 缺失法定必备条款 |
| HIGH | 显著不利或监管关注 | 单方无限解除权 |
| MEDIUM | 需协商修改 | 违约金偏高 |
| LOW | 建议优化 | 表述模糊 |
| INFO | 提示 | 引用过期法规名称 |

---

## 9. 规则引擎设计

### 9.1 规则类型

| 类型 | 说明 | 示例 |
| --- | --- | --- |
| `KEYWORD` | 必须出现 / 不得出现 | 必须含「保密」 |
| `REGEX` | 正则匹配 | `\d+\s*天` 且数值 > 90 |
| `STRUCT` | JSON 条件树 | `{ "field": "payment_days", "op": "gt", "value": 90 }` |

### 9.2 执行顺序

1. 解析文档 → chunks
2. 对每条启用规则在全量 chunks 上扫描
3. 规则命中 → 生成 Finding（source=RULE）
4. 将**命中上下文 ±N 字** + **规则描述** 送 LLM 复核（可配置跳过）
5. LLM 可：确认 / 降级 / 新增语义风险（source=LLM）
6. 去重合并（同页同主题）

### 9.3 内置示例规则（sample-data/rules/）

| code | 名称 | 严重度 |
| --- | --- | --- |
| `MISSING_NDA_CLAUSE` | 缺失保密条款 | HIGH |
| `PAYMENT_TERM_EXCEEDS_90D` | 付款周期超过 90 天 | MEDIUM |
| `UNILATERAL_TERMINATION` | 单方解除权 | HIGH |
| `EXCESSIVE_PENALTY` | 违约金比例过高 | MEDIUM |
| `MISSING_DISPUTE_RESOLUTION` | 缺失争议解决条款 | MEDIUM |
| `DATA_PRIVACY_MISSING` | 制度文档缺失个人信息保护章节 | HIGH |

---

## 10. LLM 集成

### 10.1 Prompt 结构

```
[System] 你是企业合规审查助手。仅基于给定文本片段判断风险，不得臆造未出现的条款。
[Context] 文档类型、规则名称、规则说明、证据片段（含页码）
[Output] JSON: { "confirmed": bool, "severity": "...", "title": "...", "summary": "...", "suggestion": "..." }
```

### 10.2 配置（环境变量）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `LLM_PROVIDER` | `mock` | mock / openai 兼容 |
| `LLM_BASE_URL` | — | 网关地址 |
| `LLM_MODEL` | `gpt-4o-mini` | 模型 |
| `LLM_API_KEY` | 空 | 空则 Mock |
| `LLM_TEMPERATURE` | `0.1` | 低温度保证稳定 |

### 10.3 Mock 模式

- 规则命中 → Mock 固定返回「已确认 + 模板建议」
- 无 Key 可完整演示审核流程（对齐 ai-service-agent 体验）

---

## 11. API 设计（MVP）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/documents/upload` | multipart 上传 |
| GET | `/api/documents` | 文档列表 |
| GET | `/api/documents/{id}` | 文档详情 + 解析状态 |
| GET | `/api/documents/{id}/chunks` | 文本块（分页） |
| GET/POST/PUT/DELETE | `/api/rules` | 规则 CRUD |
| POST | `/api/reviews` | 创建审核任务 `{ documentId }` |
| GET | `/api/reviews/{id}` | 任务状态 |
| GET | `/api/reports/{taskId}` | 风险报告详情 |
| GET | `/api/reports/{taskId}/export` | 导出 Markdown / JSON |
| GET | `/api/dashboard/overview` | 任务统计、风险分布 |
| GET | `/api/health` | 健康检查 |

---

## 12. 前端页面

| 页面 | 功能 |
| --- | --- |
| **文档中心** | 上传、列表、发起审核、查看历史任务 |
| **规则管理** | 规则表格、启用开关、新建/编辑表单、内置规则导入 |
| **报告详情** | 风险总览、按等级筛选、条款高亮、页码跳转、导出 |
| **看板** | 今日审核数、各等级占比、最近失败任务 |

---

## 13. 安全与合规（产品自身）

- 上传文件仅存储于本地/容器卷，**不出境调用**（除非用户配置外部 LLM）
- 日志与报告**不含真实 PII**；sample 文档均为虚构
- 文件大小限制（如 20MB）、类型白名单
- LLM 请求仅发送**必要片段**，非全文（除非用户显式开启「全文审核」增强模式 — Roadmap）

---

## 14. 验收标准

| # | 验收项 | 通过条件 |
| --- | --- | --- |
| 1 | Docker 一键启动 | `docker compose up -d --build` 后前端可访问 |
| 2 | 文档解析 | 上传 sample docx/pdf，chunks 含页码 |
| 3 | 规则命中 | 样本合同至少触发 2 条内置规则 |
| 4 | LLM/Mock 复核 | 报告含 RULE + LLM 来源 findings |
| 5 | 报告导出 | 可下载 Markdown 或 JSON |
| 6 | 单测 | 规则引擎 ≥10 case，报告聚合 ≥3 case |
| 7 | 文档 | README（中文）+ CHANGELOG + USAGE + 本规格书 |
| 8 | 无敏感信息 | 样本与配置均为虚构/demo |

---

## 15. 测试计划

```bash
# 后端
cd backend && mvn test

# 覆盖
# - RuleEngineTest: KEYWORD/REGEX/STRUCT 命中与误报
# - ReportAggregatorTest: 去重、等级排序、riskScore 计算
# - DocumentParserTest: docx/pdf 文本提取
# - ReviewOrchestratorTest: Mock LLM 全链路
```

---

## 16. 部署

```bash
cd compliance-doc-agent
cp .env.example .env    # 可选填 LLM_API_KEY
docker compose up -d --build
# 当前统一入口 http://localhost:19070；历史命令仅供设计追溯
```

---

## 17. Roadmap（Post-MVP）

- [ ] OCR（Tesseract / 云 OCR）支持扫描件 PDF
- [ ] 向量 RAG：与法规库、历史合同库比对
- [ ] 多人协作：审阅意见、驳回、复核签字
- [ ] 审批工作流与 SLA
- [ ] 对接企业微信 / 钉钉通知
- [ ] RBAC、审计日志、报告 PDF 正式模板
- [ ] 多语言文档（中英双语合同）

---

## 18. 与作品集关系

| 关联项目 | 复用/差异 |
| --- | --- |
| ai-service-agent | 复用 LLM 抽象层与 Mock 模式；场景从「客服 Agent」换为「合规审核」 |
| enterprise-rag | 后续法规 RAG 可对接其检索能力 |
| chatbi-copilot | 无直接依赖；报告看板可参考其 ECharts 用法 |

建议在 `ai-portfolio/README.md` 中增加第 7 项卡片（实现完成后）。

---

## 19. 里程碑

| 阶段 | 交付物 | 预估 |
| --- | --- | --- |
| M1 | 本规格书 + 目录骨架 + Docker 空壳 | 0.5d |
| M2 | 文档上传解析 + 规则 CRUD + 规则引擎 | 1.5d |
| M3 | 审核编排 + Mock LLM + 报告 API | 1d |
| M4 | 前端四页面 + 样本数据 | 1d |
| M5 | 单测 + README/CHANGELOG/USAGE | 0.5d |

**MVP 合计约 4–5 人日。**

---

## 20. 开放问题（实现前确认）

1. MVP 是否必须支持 PDF，还是先做 docx + txt？
   - **建议**：docx + txt 必做，PDF 用 PDFBox 基础提取一并做。
2. 报告导出格式：Markdown 是否足够？
   - **建议**：MVP 导出 Markdown + JSON；PDF 放 Roadmap。
3. 是否与 ai-portfolio 第 7 号版本号统一为 `0.1.0`？
   - **建议**：是，首发 `0.1.0`。

---

*本文档为 MVP 规格，供开发与作品集展示使用。实现时若与代码有细微偏差，以 README 与 API 为准，并回写 CHANGELOG。*
