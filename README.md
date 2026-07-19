# 合规文档 AI 审核 Agent

> 面向内审 / 合规 / 法务场景的**文档智能审核系统**：**规则引擎硬校验** + **LLM 语义审查**双层把关，**Function Calling Agent** 自主调用法规检索、版本 diff、风险评分与报告生成，审核结论全链路留痕。一条 `docker compose` 命令即可拉起，**无需任何大模型密钥**也能完整体验 Mock 审核流程。

<p>
  <img alt="CI" src="https://github.com/Hou-mingyuan/compliance-doc-agent/actions/workflows/ci.yml/badge.svg">
  <img alt="java" src="https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white">
  <img alt="spring boot" src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white">
  <img alt="rules" src="https://img.shields.io/badge/Rules%20Engine-YAML%20DSL-blue">
  <img alt="agent" src="https://img.shields.io/badge/Agent-Function%20Calling-purple">
  <img alt="vue" src="https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white">
  <img alt="license" src="https://img.shields.io/badge/License-MIT-green">
</p>

> **当前状态**：`0.1.0` MVP 可运行 — Spring Boot 后端、Vue 审核工作台、Mock LLM、规则审查、SSE 流式报告与 Docker Compose 已就绪。完整设计见 [`docs/ai-portfolio/project-07-spec.md`](docs/ai-portfolio/project-07-spec.md)。

---

## ✨ 项目亮点

- **规则引擎 + LLM 双层审核，而非纯 ChatGPT 读文档**：YAML 规则 DSL（或 Drools）先行校验必备条款、数值阈值、禁限关键词；规则无法覆盖的语义风险交由 LLM + Function Calling 深度审查，结论可解释、可复核。
- **8 个 Function Calling 工具**：已在 `ToolRegistry` 注册（**1 个接真实规则引擎** + **7 个结构化 Mock stub**），Agent 编排与 SSE 流式展示可用 — 详见下表「实现状态」。
- **贴合内审合规场景**：合同 / 内控制度 / 信息披露等文档类型；审核工作流状态机（上传 → 机审 → 人工确认 → 整改闭环）；`audit_event` 全链路留痕。
- **Mock 优先体验**：内置 Mock 法规库 + Mock LLM，无 API Key 可跑通「上传 → 规则命中 → 语义发现 → 报告预览」完整链路。
- **可插拔多模型**：OpenAI 兼容接口，`DeepSeek / 通义 / Ollama` 环境变量一键切换。
- **工程化落地**：清晰分层（controller / service / parser / rules / agent / llm），JUnit 覆盖上传、解析、规则命中与流式编排。

## 🏗️ 系统架构

```mermaid
flowchart TB
    subgraph FE["前端 (Vue3 + Vite)"]
        Upload[文档上传]
        Workbench[审核工作台]
        Admin[法规 / 规则管理]
        Report[报告导出]
    end

    subgraph BE["后端 (Spring Boot 3)"]
        API[REST + SSE]
        DOC[文档解析<br/>PDF / Word / Excel]
        RULE[规则引擎<br/>YAML DSL / Drools]
        AGENT[Agent 编排器]
        TOOLS[Function Calling 工具集]
        LLM[LLM 抽象层<br/>OpenAI 兼容 / Mock]
        WF[审核工作流 / 状态机]
    end

    DB[(MySQL / H2)]
    OSS[对象存储]
    EXT[大模型网关]

    Upload --> API --> DOC --> OSS
    API --> RULE --> DB
    API --> AGENT
    AGENT --> TOOLS --> DB
    AGENT --> LLM --> EXT
    AGENT --> WF --> DB
    Workbench -->|SSE| API
```

> 详细时序图、数据模型与 API 设计见 [`docs/ai-portfolio/project-07-spec.md`](../docs/ai-portfolio/project-07-spec.md)。

## 🧰 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.3、Spring MVC（SSE） |
| 规则引擎 | YAML DSL（MVP）/ Drools 7.x（可选） |
| 文档解析 | Apache PDFBox、TXT / Markdown / PDF |
| Agent | 自研工具注册中心 + Function Calling 编排 |
| 大模型 | OpenAI 兼容客户端（流式 + 工具调用）/ 内置 Mock |
| 持久层 | MyBatis-Plus 3.5、MySQL 8（生产）/ H2（本地） |
| 前端 | Vue 3、Vite、TypeScript |
| 部署 | Docker、docker-compose |

## 📁 目录结构

```
compliance-doc-agent/
├── backend/                          # Spring Boot 3 后端
│   ├── src/main/java/com/portfolio/compliance/
│   │   ├── agent/          # SSE 审核编排与 Mock LLM 输出
│   │   ├── controller/     # REST / SSE API
│   │   ├── entity/mapper/  # H2 持久化与 MyBatis-Plus
│   │   ├── llm/            # Mock / OpenAI 兼容配置
│   │   ├── parser/         # 文档解析与分块
│   │   └── rules/          # 规则引擎
│   └── src/main/resources/samples/   # 内置演示文档
├── frontend/                         # Vue3 审核工作台
├── docs/
│   ├── USAGE.md                      # 使用指南
│   └── architecture.md               # 架构文档
├── DEPLOYMENT.md                     # 部署与运维
├── SECURITY.md                       # 安全策略
├── PERFORMANCE_REPORT.md             # 压测报告
├── performance/k6-smoke.js           # k6 只读 API smoke
├── docker-compose.yml                # Docker Desktop 一键启动
├── .env.example                      # 零密钥 Mock 默认配置
├── VERSION
├── CHANGELOG.md
└── README.md
```

## 🚀 快速开始

### Docker 一键启动（推荐）

```bash
cd compliance-doc-agent
cp .env.example .env        # 默认 Mock 模型即可体验
docker compose up -d --build
```

启动后：

- 前端界面：http://localhost:5173
- 后端接口：http://localhost:8080/api/health

体验流程：

1. 上传 `backend/src/main/resources/samples/合同条款片段.txt` → 文档列表出现记录；
2. 查看 LLM 语义审查产出的隐性风险建议；
3. 点击「开始审核」→ 报告页通过 SSE 实时显示风险项、流式分析与摘要。

详细步骤见下方 [演示指南](#演示指南) 与 [docs/USAGE.md](docs/USAGE.md)。部署运维见 [DEPLOYMENT.md](DEPLOYMENT.md)，安全策略见 [SECURITY.md](SECURITY.md)。

## 演示指南

面向作品集评审与首次体验。**本项目无需登录账号**；默认 Mock LLM 零密钥即可跑通完整审核链路。

### 演示账号说明

| 项目 | 说明 |
| --- | --- |
| 登录 / 演示账号 | **无** — MVP 未接入 RBAC，打开前端即可使用 |
| 体验方式 | `cp .env.example .env` 后 `docker compose up -d --build`，保持 `LLM_PROVIDER=mock` |
| 真实 LLM | 可选 BYOK：设置 `LLM_PROVIDER=openai` 与 `LLM_API_KEY` |

### 核心演示流程

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 打开 http://localhost:5173 | 审核工作台加载 |
| 2 | 上传 `backend/src/main/resources/samples/合同条款片段.txt` | 文档列表出现记录 |
| 3 | 点击「开始审核」 | 跳转报告页，SSE 流式输出 |
| 4 | 观察风险项 / 详细分析 / 摘要 | 命中 R-CON-001、R-PII-001 等规则；Mock LLM 流式 narrative |
| 5 | 审核完成 | 显示报告 ID 与风险分布 |

命令行等价验证见 [DEPLOYMENT.md §5](DEPLOYMENT.md#5-健康检查与-smoke)。

### 验收命令

```bash
curl -f http://localhost:8080/api/health          # 含 "llmProvider":"mock"
curl -f http://localhost:8080/api/documents       # 200
cd backend && mvn -B test                         # JUnit 全绿
```

压测基线见 [PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md) 与 `performance/k6-smoke.js`。

### 本地开发

```bash
cd backend
mvn spring-boot:run           # 默认 H2 + Mock LLM，端口 8090

cd frontend
npm install && npm run dev    # http://localhost:5173，/api 代理到 8090
```

## ⚙️ 配置说明

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `LLM_PROVIDER` | `mock` / `openai` | `mock` |
| `LLM_BASE_URL` | OpenAI 兼容网关 | `https://api.openai.com/v1` |
| `LLM_MODEL` | 模型名 | `gpt-4o-mini` |
| `LLM_API_KEY` | 密钥（留空回退 Mock） | 空 |
| `SPRING_DATASOURCE_URL` | H2 / MySQL JDBC URL | H2 内存库 |
| `BACKEND_HOST_PORT` | Docker 后端宿主端口 | `8080` |
| `FRONTEND_PORT` | Docker 前端宿主端口 | `5173` |

## 🔌 API 概览（MVP）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查 |
| GET | `/api/documents` | 已上传文档列表 |
| POST | `/api/documents/upload` | 上传文档，触发审核流水线 |
| POST | `/api/compliance/audit/stream/{docId}` | SSE：审核进度 + 风险项 + 摘要 |
| POST | `/api/compliance/analyze` | JSON 文本分析备用接口 |

## 🧠 Agent 工具一览

> 工具名与 `ToolNames.java` / `ComplianceAgentToolStubs` 一致。**Round-7**：README 与代码实现对齐，明确 stub 与真实实现边界。

| 工具名 | 用途 | 实现状态 | 关键参数 |
| --- | --- | --- | --- |
| `check_rules` | 规则包硬校验（真实引擎） | ✅ **真实** · `ComplianceRuleEngine` | `doc_content`, `rule_pack_id` |
| `compare_clause` | 条款 / 版本 diff | 🔶 **Mock stub** · 结构化 findings | `doc_id`, `base_version`, `target_version` |
| `summarize_risks` | 汇总风险评分 | 🔶 **Mock stub** | `doc_id`, `finding_ids` |
| `search_regulation` | 检索法规 / 内规库 | 🔶 **Mock stub** | `keyword`, `category` |
| `get_document_section` | 按章节 / 页码取原文 | 🔶 **Mock stub** | `doc_id`, `section_id` |
| `extract_entities` | 抽取甲乙方、金额、日期等 | 🔶 **Mock stub** | `doc_id` |
| `generate_audit_report` | 生成审核报告 | 🔶 **Mock stub** | `doc_id`, `format` |
| `create_remediation_task` | 创建整改任务 | 🔶 **Mock stub** | `doc_id`, `finding_id`, `assignee` |

**说明**：stub 工具在 Mock LLM / 零密钥演示下返回**固定结构**的 findings，便于 Agent 编排与 SSE 可视化验收；Phase 2 目标是将 🔶 项替换为真实检索 / 抽取 / 报告生成实现。

## 📋 审核工作流

```
UPLOADED → PARSING → RULE_REVIEW → LLM_REVIEW → PENDING_CONFIRM
    → APPROVED / REJECTED → REMEDIATION → 重新上传 → ARCHIVED
```

非法状态流转会被拒绝；每次变更写入 `audit_event` 满足内审留痕。

## 🗺️ Roadmap

### Phase 1 — MVP

- [x] 文档上传 + TXT / Markdown / PDF 解析 + 分块
- [x] 规则引擎 + 内置规则
- [x] Mock LLM 语义审查
- [x] 审核工作台（上传列表 + SSE 报告）
- [x] Docker Compose 一键启动

### Phase 2 — Agent 增强（进行中 · Round-7 现状标注）

- [x] **8 个工具注册**到 `ToolRegistry`（`check_rules` 真实 · 其余 7 个 Mock stub）
- [x] Agent 编排 + 审核 SSE 流式（Mock LLM 零密钥可演示）
- [ ] 7 个 stub 工具替换为真实实现（法规检索 / 实体抽取 / 报告 PDF 等）
- [ ] Agent 对话追问 UI（工具调用可视化 polish）
- [ ] 接入真实 LLM（OpenAI 兼容，`LLM_API_KEY`）

### Phase 3 — 生产化

- [ ] 法规库向量检索（对接 Enterprise RAG）
- [ ] Drools 复杂规则 + RBAC 多租户
- [ ] 整改任务闭环 + 看板统计

## 🔗 相关项目

| 项目 | 关系 |
| --- | --- |
| [AI Service Agent](../ai-service-agent/) | Agent 编排与 Function Calling 参考实现 |
| [Enterprise RAG](../enterprise-rag/) | 法规知识库向量检索（可选集成） |
| [AI Portfolio](../ai-portfolio/) | 作品集总览 |
| [Project 07 规格书](../docs/ai-portfolio/project-07-spec.md) | 完整设计文档 |

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。内置法规条文与样例文档均为虚构，仅用于演示。
