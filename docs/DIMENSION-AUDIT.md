# Compliance Doc Agent · 十维审计报告（Round-6）

> **审计日期**：2026-07-06（**Round-6 复测** · project-hub-1 P2 终验 · k6 只读复跑确认）  
> **范围**：`compliance-doc-agent` 全栈（Spring Boot 3 · Vue 3 · H2/MySQL · Mock LLM · Docker · Hub Profile）  
> **评分**：1–10 分（10 = 生产标杆级）  
> **关联**：[PRODUCTION-READINESS.md](../../ai-portfolio/PRODUCTION-READINESS.md) · [PERFORMANCE_REPORT.md](../PERFORMANCE_REPORT.md) · [GAP-MATRIX.md](../../ai-portfolio/docs/GAP-MATRIX.md)  
> **Round-4 副本**：[projects/compliance-doc-agent/DIMENSION-AUDIT.md](../../projects/compliance-doc-agent/DIMENSION-AUDIT.md)

---

## 总览

| 维度 | 得分 | 等级 | 主要 gap |
| --- | ---: | --- | --- |
| 1. 文档与 README | **8** | 良好 | 状态机 mermaid、CHANGELOG 滞后 |
| 2. Docker 与部署 | **8** | 良好 | 8082 端口冲突需文档化；prod HTTPS 未实采 |
| 3. CI / CD | **9** | 优秀 | SSE soak nightly 未建 |
| 4. 性能与压测 | **9** | 优秀 | 上传端点并发压测待补 |
| 5. 安全基线 | **8** | 良好 | RBAC / 多租户 N/A by design |
| 6. 测试与质量 | **9** | 优秀 | 工具 stub 单测缺 |
| 7. API 与架构 | **9** | 优秀 | 8 工具多为 Mock stub，真 LLM 编排待 Phase 2 |
| 8. 前端 UX | **9** | 优秀 | 取消审核 / 进度条 UX 待补 |
| 9. 演示与作品集 | **9** | 优秀 | 录屏 / demo 一键脚本命名未统一 |
| 10. 可维护性与工程化 | **8** | 良好 | OpenAPI 生成、规则热加载 UI 缺 |
| **加权平均** | **8.6** | **作品集就绪+** | — |

**结论**：**规则引擎 + LLM Agent 双层审核** + **Mock 零密钥 SSE 闭环**仍是核心卖点。**Round-5**：SSE Mock **30min soak** + Vitest **14** 测；均分 **8.4 → 8.6**。**Round-6**：k6 只读复跑 P95 **55.9 ms**（project-hub-2）。**P2 终验（project-hub-1）**：Vitest **14/14** · SSE soak 60s **112 runs / allOk** · p95 elapsed **66.3 ms** · p95 TTFB **65.9 ms**（`:8080` 独立 compose）。

---

## 1. 文档与 README（8/10）

### 现状

- README **四要素齐全**：演示账号表（明确无登录）、核心流程 5 步、验收命令、链到 SECURITY / DEPLOYMENT / PERFORMANCE_REPORT。
- 架构 **mermaid flowchart**、8 工具表、审核工作流 ASCII、Roadmap 三阶段清晰。
- `docs/USAGE.md`、`DEPLOYMENT.md`、`SECURITY.md`、`PERFORMANCE_REPORT.md` 互链完整。
- CSDN 正文 [05-compliance-doc-agent.md](../../docs/csdn/05-compliance-doc-agent.md) 就绪。
- Hub 验证记录 [verify/compliance-doc-agent.md](../../ai-portfolio/docker/verify/compliance-doc-agent.md) 记录 8091/5174 并行端口策略。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| P2 | README 补充审核状态机 **mermaid stateDiagram**（与 §审核工作流 ASCII 对齐） | 评审一眼看懂流转 |
| P2 | `CHANGELOG.md` 同步 MVP 实装项（当前仍写「Planned 待实现」） | 版本叙事一致 |
| P3 | 8 工具 API 表链 SpringDoc / OpenAPI 导出 | 契约可发现 |

---

## 2. Docker 与部署（8/10）

### 现状

- **dev 栈**：`backend`（healthcheck）+ `frontend-dev`（Vite 热更新，5173）。
- **prod profile**：Nginx 静态前端 + `/api` 反代，单端口 8080。
- Mock LLM 默认，`docker compose up -d --build` 零密钥可跑通上传 → SSE 审核。
- Hub **`compliance` Profile** 经 `start-profile.ps1` 调用子项目 compose；`verify-all.ps1` **11/11 含 compliance**（2026-07-06）。
- H2 卷 `h2data`、环境变量与 `.env.example` 对齐。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| P2 | 默认 `COMPLIANCE_BACKEND_PORT=8082` 与 chatbi 冲突 — 在 README 置顶「并行 Hub 用 8091」 | 降低首次启动失败率 |
| P2 | 生产 MySQL + HTTPS 反代（Caddy/Nginx）模板补全并标注「待实采」 | 部署维度 8→9 |
| P3 | 报告 PDF/Word 导出所需字体 / LibreOffice 容器依赖文档 | Phase 2 导出前置 |

---

## 3. CI / CD（9/10）

### 现状

- GHA `.github/workflows/ci.yml` **三路 job**：
  - `backend`：`mvn -B clean test`（`LLM_PROVIDER=mock`）
  - `frontend`：`npm ci && npm run build`
  - `docker-dev-smoke`：compose build + up backend，curl 健康检查含 `"llmProvider":"mock"`（**Round-4 已落地**，原 P2 项）
- README CI badge 可点击。
- 后端 **28** JUnit tests、前端 `vue-tsc --noEmit` + Vite build 通过。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| P2 | CI 增加前端 Playwright smoke（或复用 `scripts/verify-audit-ux.py`） | 防 SSE UI 回归 |
| P3 | SSE audit stream **nightly soak**（Mock LLM 60s） | 流式稳定性证据 |
| P3 | 依赖漏洞扫描设为 required | 供应链可见 |

---

## 4. 性能与压测（9/10）

### 现状

- `performance/k6-smoke.js` 覆盖只读 API：`GET /api/health`、`GET /api/documents`。
- **实测（2026-07-06）**：Docker backend `:8080`，**20 VU / 1m**，**P95 118.09 ms**，**0.00% fail**，3393/3393 checks 通过 — 已写入 [PERFORMANCE_REPORT.md](../PERFORMANCE_REPORT.md)。
- **Round-6 复跑**（project-hub-2 · 20 VU × 30s）：P95 **55.9 ms** · 580 iter · 0% 失败 · 1740/1740 checks — 基线稳定。
- **Round-5**：`scripts/sse-audit-soak.py` **30min** Mock SSE 长稳 soak（`docs/evidence/sse-soak-30min-20260706.log`）；60s 快速验证 **104 runs / allOk** · p95 elapsed **207.8 ms**。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| ~~P1~~ | ~~k6 只读 smoke 实测写入 PERFORMANCE_REPORT~~ | **✅ Round-4** |
| ~~P2~~ | ~~SSE audit Mock soak 脚本~~ → `sse-audit-soak.py` + **30min 长稳** ✅ Round-5 | D4 8→9 |
| P2 | `POST /api/documents/upload` 并发 + 5MB 边界压测 | 上传瓶颈可见 |
| P3 | 真实 LLM 切换后 token 延迟专项报告 | Phase 2 生产评估 |

---

## 5. 安全基线（8/10）

### 现状

- [SECURITY.md](../SECURITY.md)：漏洞报告渠道、BYOK 密钥策略、上传 ≤5MB、CORS dev 默认 `*`、生产基线 checklist。
- 规则 YAML DSL 硬校验 + Agent 语义层；`audit_event` 工作流留痕。
- Mock 法规 / 样例文档标注虚构；H2 console 生产禁用指引。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| P2 | 上传 MIME 白名单 + 网关层病毒扫描 Roadmap | 合规评审常见问项 |
| P3 | 多租户隔离 — **当前 N/A by design** | — |
| P3 | SSE 端点 CSRF / 会话绑定（接入 RBAC 后） | Phase 3 |

---

## 6. 测试与质量（9/10）

### 现状

- **JUnit 28 tests**（0 fail）：解析、规则、上传、SSE 编排全覆盖。
- `scripts/verify-audit-ux.py` 支持手工 UX 验证。
- **Round-5 Vitest**：`highlight.spec.ts` + `sseParse.spec.ts`（`npm test` · **14** 测 · CI frontend job）；覆盖 CRLF 帧解析、重叠高亮、hl-recent、空 payload 边界。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| ~~P2~~ | ~~前端 Vitest：highlight + SSE 解析~~ → **14 tests** ✅ Round-5 | D6 8→9 |
| P2 | `ComplianceAgentToolStubs` 各工具 execute 契约单测 | 工具注册回归 |
| P3 | 状态机非法跳转集成测试扩充 | 工作流健壮性 |

---

## 7. API 与架构（9/10）

### 现状

- 审核工作流状态机完整；非法流转拒绝 + `audit_event` 留痕。
- **8 个 Function Calling 工具**已在 `ToolRegistry` 注册（`check_rules` 接真实规则引擎，其余 7 个为结构化 Mock stub）。
- REST + SSE 分层清晰；Mock 全链路可验收。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| P1 | ~~README Roadmap 与 8 工具 stub 现状标注~~ ✅ Round-7 · 工具表对齐 `ToolNames` | D7 |
| P2 | 真 LLM Function Calling 编排 + 工具调用可视化 | 架构 9→10 |
| P3 | SpringDoc OpenAPI 自动生成 | 契约标准化 |

---

## 8. 前端 UX（9/10）

### 现状（P6 批次）

- SSE **事件流时间线**、阶段步骤条、文档 **diff 高亮**、统一 **Toast**、严重度统计。
- Playwright / `verify-audit-ux.py` 验证通过。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| P2 | 审核 **进度条** + **取消审核** UX | 长文档体验 |
| P3 | 深色模式、移动端只读报告 | 展示多样性 |
| P3 | Agent 工具调用可视化面板 | Phase 2 |

---

## 9. 演示与作品集（9/10）

### 现状

- 零密钥 Mock 完整 SSE 闭环；Hub `compliance` Profile + verify-all 纳入。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| P2 | `demo-mock.ps1` 一键脚本 | 评审复现 <2 min |
| P2 | `docs/screenshots/` 截图入库 | 离线作品集 |
| P3 | 30s 演示录屏 | 视频引流 |

---

## 10. 可维护性与工程化（8/10）

### 现状

- 前后端分离、Vite proxy 可切换、ToolRegistry 可扩展、Hub 脚本独立 compose 路径。

### Gap

| 优先级 | 动作 | 预期收益 |
| ---: | --- | --- |
| P2 | OpenAPI / 前端类型生成 | 契约同步 |
| P3 | 规则包热加载 UI | 运维友好 |
| P3 | CHANGELOG 与 git tag 发布自动化 | 版本治理 |

---

## 优先行动清单（Top 8）

| # | 优先级 | 动作 | 维度 |
| ---: | ---: | --- | --- |
| 1 | **P1** | ~~k6 只读 smoke 实测~~ ✅ | D4 |
| 2 | ~~**P1**~~ | ~~统一 README Roadmap 与 8 工具 stub 现状标注~~ ✅ Round-7 | D7 |
| 3 | ~~**P2**~~ | ~~SSE audit Mock soak 30min~~ → `sse-audit-soak.py` + evidence log ✅ | D4 |
| 4 | **P2** | README 状态机 mermaid + 截图目录 | D1/D9 |
| 5 | ~~**P2**~~ | ~~前端 Vitest~~ → highlight + sseParse **14** tests ✅ Round-5 | D6 |
| 6 | **P2** | `demo-mock.ps1` 一键演示 | D9 |
| 7 | **P2** | Hub 并行端口说明置顶 | D2 |
| 8 | **P3** | 生产 HTTPS + MySQL 实采 | D2/D5 |

---

## 矩阵对照

| 检查项 | 状态 |
| --- | --- |
| 九维生产矩阵全维 | ✓ |
| 多租户 | **N/A (by design)** |
| Hub Profile `compliance` | ✓ |
| Mock 零密钥闭环 | ✓ |
| k6 只读实测 | ✓ |
| CI docker smoke | ✓ |

---

## 相关文档

- [PERFORMANCE_REPORT.md](../PERFORMANCE_REPORT.md)
- [SECURITY.md](../SECURITY.md)
- [DEPLOYMENT.md](../DEPLOYMENT.md)
- [docs/USAGE.md](./USAGE.md)
- [projects 副本](../../projects/compliance-doc-agent/DIMENSION-AUDIT.md)

*Round-6 均分 **8.6**（k6 复跑确认）· 下一目标：demo-mock.ps1 + README 状态机 → **8.7+**。*
