# Compliance Doc Agent 系统架构

## 目标与边界

系统把上传文档转为可定位证据，执行确定性规则、法规目录检索、版本比较和实体抽取，再由人工完成风险判断、整改、复审和报告导出。

默认 Mock LLM 只组织工具摘要。风险项由工具根据输入生成并持久化，模型不能直接写入 findings，也不能替代人工法律判断。

## 运行结构

```text
Browser :19070
  -> Nginx static frontend
  -> /api reverse proxy
Spring Boot :19071
  -> Security / tenant context / request id
  -> Document parser + locator
  -> Review workflow + deterministic tool orchestrator
  -> Regulation catalog + rule engine + entity / clause analysis
  -> Remediation + report + audit trail
  -> H2 file database (local RC)
```

前端是 Vue 3 单页工作台，包含文档、审核、整改、演示法规和审计视图。后端使用 Spring Boot 3、Spring Security、JDBC/MyBatis-Plus、Apache PDFBox、Apache POI 和 H2。

## 核心数据流

1. 上传服务校验大小、扩展名、签名和容器结构，计算 SHA-256。
2. 解析器生成正文与页码、章节、段落、字符 span；分块记录可回到原文。
3. 审核状态由 `CREATED` 原子转换为 `RUNNING`，同文档只允许一个活动运行。
4. 编排器使用服务端 `ToolContext` 执行规则、实体、版本、法规、定位和汇总工具。
5. 工具结果、findings、实体、引用和执行轨迹在同一审核运行下持久化。
6. Mock 或真实 LLM 只能根据已完成工具输出生成叙述；失败不会伪造风险或法规。
7. SSE 推送阶段、工具、风险、叙述和汇总，最终进入 `PENDING_REVIEW`。
8. 人工将每条风险标记为误报或确认；确认项可进入整改任务闭环。
9. 已解决风险完成复审后才能批准；报告从审核快照生成并保存内容哈希。
10. 所有关键转换写入按租户串联的审计哈希链。

## 深模块

| 模块 | 主要职责 | 关键类 |
| --- | --- | --- |
| 文档入口 | 校验、去重、版本、解析、分块和原文读取 | `DocumentUploadService`, `DocumentParser`, `DocumentLocator` |
| 审核工作流 | 运行互斥、取消、完成、失败、复核与批准 | `ReviewWorkflowService`, `ReviewStore` |
| Agent 编排 | 受信任上下文、工具顺序、超时、错误和轨迹 | `ComplianceAgentOrchestrator`, `ToolRegistry` |
| 确定性分析 | 规则、版本差异、实体、风险分数 | `ComplianceRuleEngine`, `ClauseComparisonService`, `EntityExtractor` |
| 法规目录 | 带版本、日期、范围和来源的 DEMO 条目检索 | `RegulationCatalog` |
| 整改 | 指派、证据、复审、关闭与重开 | `RemediationService` |
| 报告 | 快照摘要、版本、幂等、DOCX、SHA-256 | `ReportService` |
| 权限与审计 | Basic Auth 演示身份、RBAC、租户隔离、哈希链 | `SecurityConfig`, `ActorContext`, `AuditTrail` |

## 8 个 Agent 工具

| 工具 | 输入驱动行为 | 主要输出 |
| --- | --- | --- |
| `check_rules` | 规则包 + 文档类型 + 当前正文 | 真实规则命中、证据 span、规则版本 |
| `compare_clause` | 当前版本与父版本 | 新增、删除、修改和风险变化 |
| `summarize_risks` | 当前运行已持久化 findings | 分数、等级和分类计数 |
| `search_regulation` | 查询词、范围、日期、topK | 实际目录命中；零命中为空 |
| `get_document_section` | chunk/页/章/段定位参数 | 对应原文和位置信息 |
| `extract_entities` | 当前正文 | 主体、金额、日期、责任、续期等 span |
| `generate_audit_report` | 已批准或可报告的审核快照 | 真实 DOCX 报告元数据 |
| `create_remediation_task` | 已确认 finding、负责人和截止日 | 持久化且同 finding 幂等的任务 |

每个工具提供 JSON schema、必填校验、最小角色、服务端超时、结构化错误和 `tool_execution` 审计。模型给出的文档 ID、正文、租户和操作者会被服务端上下文覆盖。

## 状态机

审核：

```text
CREATED -> RUNNING -> PENDING_REVIEW -> APPROVED
                  \-> CANCELLED
                  \-> FAILED
PENDING_REVIEW -> REMEDIATION -> RECHECK -> APPROVED
APPROVED/RECHECK -> REMEDIATION  (整改重开)
```

风险：

```text
OPEN -> FALSE_POSITIVE
OPEN -> CONFIRMED -> REMEDIATION_REQUIRED -> RESOLVED
```

整改：

```text
OPEN/REOPENED/REJECTED -> IN_PROGRESS -> EVIDENCE_SUBMITTED
EVIDENCE_SUBMITTED -> VERIFIED -> CLOSED -> REOPENED
EVIDENCE_SUBMITTED -> REJECTED
```

状态更新带旧状态和版本号条件。冲突、重复或非法转换返回 HTTP `409`，不会靠前端隐藏按钮代替服务端校验。

## 数据模型

| 表 | 用途 |
| --- | --- |
| `compliance_document` | 租户文档、格式、摘要、版本和解析状态 |
| `compliance_document_chunk` | 原文分块及页/章/段位置 |
| `compliance_check` | 兼容规则检查记录 |
| `regulation_entry` | DEMO 法规/内规目录和版本有效期 |
| `review_run` | 审核运行、规则版本、模型模式和状态 |
| `risk_finding` | 风险、证据、位置、建议和人工结论 |
| `finding_regulation` | 风险与实际法规命中的引用关系 |
| `document_entity` | 实体类型、span、置信度和确认字段 |
| `remediation_task` | 整改指派、状态、版本和复审意见 |
| `remediation_evidence` | 脱敏整改证据 |
| `audit_report` | 报告快照、版本、摘要和二进制内容 |
| `tool_execution` | 工具输入摘要、结果、耗时和错误码 |
| `audit_event` | 按租户前向哈希的业务审计事件 |

H2 使用 `schema.sql` 幂等初始化，测试使用随机内存库；Compose 使用 named volume 中的文件库。生产数据库兼容性和迁移策略必须在目标环境单独验证。

## SSE 契约

`POST /api/reviews/stream/{documentId}` 返回：

```text
start -> stage* -> tool* -> finding* -> narrative* -> summary -> done
```

终止分支为 `cancelled` 或 `error`。异步任务在提交前捕获不可变 `ActorContext`，不依赖线程本地安全上下文。Nginx 对 SSE 禁用缓冲。

## 安全与报告边界

- 除健康检查外所有 API 都需认证，资源查询执行服务端租户约束。
- 系统管理员可跨租户运维，但读取行为额外审计。
- DOCX 报告包含审核、风险、原文证据、法规引用、人工意见、整改和版本信息。
- 报告哈希和审计链用于工程完整性检查，不等同于法定存证。
- 内置法规为明确标注的 DEMO 数据，不是权威法规库。

接口、使用、安全和部署细节分别见 [API.md](API.md)、[USAGE.md](USAGE.md)、[../SECURITY.md](../SECURITY.md) 和 [../DEPLOYMENT.md](../DEPLOYMENT.md)。
