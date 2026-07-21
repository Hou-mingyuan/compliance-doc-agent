# Compliance Doc Agent 完成态审计

审计日期：2026-07-20。结论基于当前工作树的自动测试、Docker 运行、真实浏览器、性能数据和报告渲染，不沿用旧轮次自评分。

## 验收矩阵

| 维度 | 结论 | 证据 |
| --- | --- | --- |
| 核心功能 | PASS | 8 工具、5 样例、人工整改闭环和报告均真实运行 |
| 文档解析 | PASS | TXT/MD/PDF/DOCX、5MB、签名、扫描/加密/ZIP 防护测试 |
| 规则与法规 | PASS | 规则包 1.0.0、实际法规命中、零命中为空、引用关系持久化 |
| 定位与实体 | PASS | 页/章/段/span/chunk、主体/金额/日期/责任/续期及脱敏证件 |
| 工作流 | PASS | 取消、误报、确认、指派、证据、退回/验收、关闭、重开、批准 |
| 权限与租户 | PASS | 四角色、资源级隔离、跨租户 403、系统管理员跨租户审计 |
| 审计与报告 | PASS | SHA-256 前向链、快照报告、幂等、版本、下载哈希、逐页渲染 |
| 页面与交互 | PASS | 三档视口、无横向溢出、控制台 0 error/0 warning |
| 性能 | PASS | 读 p95 151.31ms、写 p95 264.10ms、Lighthouse 93/100 |
| 开箱启动 | PASS | Compose `19070/19071`、健康、5.1 秒自动业务验收 |

## 工具替换证据

旧 `ComplianceAgentToolStubs` 已删除。当前 `ComplianceAgentTools` 提供：

| 工具 | 验证方式 |
| --- | --- |
| `check_rules` | 不同文档类型和正文产生不同规则命中 |
| `compare_clause` | v1/v2 输出增删改与风险变化 |
| `summarize_risks` | 从当前 review findings 计算分数，非固定 62 |
| `search_regulation` | 只返回数据库实际命中；无命中为空 |
| `get_document_section` | 返回真实 chunk 与位置 |
| `extract_entities` | 从 span 提取并对身份证脱敏 |
| `generate_audit_report` | 生成可下载的真实 DOCX |
| `create_remediation_task` | 写入数据库并执行状态机与幂等 |

后端工具注册、超时和错误测试与工作流集成测试共同覆盖 schema、权限、资源绑定和审计。Mock LLM 不在 findings 生成链路中。

## 自动化与运行证据

- 后端最终门禁：51 tests，0 failure/error/skip
- 前端最终门禁：ESLint 0 warning、typecheck 通过、3 个测试文件共 18 个测试通过、生产构建通过
- Docker 自动验收：5 个脱敏案例，规则期望 TP=4、FP=0、FN=0
- Docker 闭环：整改 `CLOSED`、审核 `APPROVED`，审计链 79 个事件有效
- 真实浏览器闭环：文档 `DOC-00007`、审核 `REV-3E991F95CA604258`、整改 `TASK-EA3761B4C93D44EB`
- 浏览器最终报告 SHA-256：`5d6d79a4cb947eb6d1e37b9fe98ad4744d34f0940f670e5ff17c99f63af9358c`
- Docker 最终报告 SHA-256：`dca50fbf046cc4705dd1d7ba5ae1b629e6c6b62336bd0665cc9143bfe4c84dd6`
- Docker 报告渲染：`docs/evidence/docker-acceptance/report-render-final/`，PDF SHA-256 `ecbfcb22b44cfee6cf424fd6d4b53713342b6688b7f0b2280905872713e0fdae`

固定样例 precision/recall 为 1.0 只代表仓库预期与内置规则一致，不表示法律判断准确率。

## 已知边界

- 扫描 PDF 没有 OCR，会明确拒绝
- 法规是非权威 DEMO 数据；真实法规授权和更新治理是外部条件
- 演示 Basic Auth、H2 和较低 BCrypt cost 仅用于本地脱敏验收
- 审计链未做第三方锚定，不构成法定存证
- 没有执行生产发布，也没有用真实客户文档或真实密钥测试

在上述边界内未发现阻断发布候选的已知 P0/P1 或核心流程 P2。本轮全量门禁、secret scan、`git diff --check`、报告逐页查看、Compose 端口核对和工作树审计均已通过。
