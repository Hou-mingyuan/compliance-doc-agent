# Compliance Doc Agent

证据可追溯、人工可复核、整改可闭环的合规文档审核工作台。当前版本为 `0.1.0` 发布候选版，默认使用脱敏样例、演示法规集和零密钥 Mock 文本模式。

Mock 只整理真实工具输出，不生成风险项。风险项来自规则、法规检索、版本比较和实体分析的确定性结果，不使用固定 findings 冒充审核。

## 一键启动

前置条件：Docker Desktop / Docker Engine 与 Compose v2。

```bash
copy .env.example .env
docker compose up -d --build
```

Linux/macOS 使用 `cp .env.example .env`。

启动完成后：

- 工作台：<http://localhost:19070>
- 后端健康：<http://localhost:19071/api/health>
- 前端 `/api` 由 Nginx 反向代理到后端；SSE 已关闭代理缓冲。

检查状态：

```bash
docker compose ps
curl http://localhost:19071/api/health
```

停止服务但保留 H2 数据卷：

```bash
docker compose down
```

彻底删除演示数据：

```bash
docker compose down -v
```

## 演示账户

| 身份 | 账户 | 密码 | 主要权限 |
| --- | --- | --- | --- |
| 业务用户 | `user@demo.local` | `demo-change-me` | 上传、发起审核、处理本人整改 |
| 审核员 | `reviewer@demo.local` | `demo-change-me` | 人工判断、证据验收、批准、报告 |
| 合规管理员 | `compliance@demo.local` | `demo-change-me` | 创建/关闭整改、法规检索、审计查看 |
| 系统管理员 | `admin@demo.local` | `admin-change-me` | 跨租户运维视图，业务操作仍按资源租户审计 |
| 租户 B 用户 | `tenant-b@demo.local` | `demo-change-me` | 用于验证跨租户隔离 |

演示账户只用于本地脱敏环境。对外部署必须设置 `DEMO_AUTH_ENABLED=false` 并接入正式身份源。

## 核心流程

1. 使用业务用户上传 `samples/contract-v2-risky.txt`，选择“合同”。
2. 发起审核，观察 SSE 阶段、8 个工具轨迹、原文命中和实体 span。
3. 使用审核员逐条确认或驳回风险，并填写人工意见。
4. 使用合规管理员为已确认风险创建整改任务。
5. 业务用户开始任务并提交脱敏证据；审核员验收；合规管理员关闭。
6. 审核员复审批准并生成 DOCX 报告。
7. 在审计页校验租户内 SHA-256 前向哈希链。

自动执行同一条完整链路：

```bash
python scripts/verify-complete.py --base http://127.0.0.1:19070
```

失败时脚本非零退出并写入 `docs/evidence/acceptance-failure.json`；成功时写入 `acceptance-latest.json` 与实际 DOCX。

## 已实现能力

| 能力 | 当前实现 |
| --- | --- |
| 文档解析 | TXT、Markdown、文本型 PDF、DOCX；5MB；签名/加密/二进制/ZIP 防护 |
| 结构定位 | 页码、章节、段落、字符 span、chunk、SHA-256 |
| 规则审核 | 版本化规则包、文档类型适用范围、包含/缺失规则、真实命中 |
| 法规检索 | 数据库目录；版本、生效/失效日期、范围、来源；零命中返回空 |
| 条款比较 | 文档版本的新增、删除、修改及规则风险变化 |
| 实体抽取 | 主体、金额、日期、责任、自动续期、脱敏身份证；span 与置信度 |
| 风险汇总 | 基于当前运行持久化 findings 计算初始分；人工结论单独留痕 |
| 人工工作流 | 复核、误报、确认、整改、证据、复审、批准；非法转换拒绝 |
| 权限与租户 | USER、REVIEWER、COMPLIANCE_ADMIN、SYSTEM_ADMIN；服务端租户过滤 |
| 审计 | 请求主体、状态变化、工具调用、报告事件；数据库内 SHA-256 哈希链 |
| 报告 | 基于审核快照生成 DOCX，版本化、幂等、SHA-256 下载校验 |

### Agent 工具

以下工具均有 JSON schema、必填校验、角色权限、超时、结构化错误和执行审计：

- `check_rules`
- `compare_clause`
- `summarize_risks`
- `search_regulation`
- `get_document_section`
- `extract_entities`
- `generate_audit_report`
- `create_remediation_task`

LLM 不能指定受信任的 `doc_id`、正文、租户或操作者；编排器会用服务端上下文覆盖这些字段。

## 格式与边界

- 扫描 PDF 未实现 OCR，会明确拒绝，不会把空白文档标记为审核成功。
- 内置法规是标有 `DEMO` 的脱敏工程样例，不是权威法规原文。
- Mock 模式只组织工具摘要；接入 OpenAI-compatible 服务时也不允许模型直接持久化 findings。
- 审计哈希链可检测数据库事件被改写，不等同于第三方时间戳、电子签名或法定存证。
- 结果供具备职责的人员复核，不替代律师意见或法定认证。

## 本地开发

要求 Java 17+、Maven 3.9+、Node.js 22+。

后端（`19071`）：

```bash
cd backend
mvn spring-boot:run
```

前端（`19070`，代理到 `19071`）：

```bash
cd frontend
npm ci
npm run dev
```

默认文件型 H2 位于 `backend/data/`，已被 Git 忽略。测试使用随机内存 H2。

## 质量命令

```bash
cd backend
mvn -B clean test

cd ../frontend
npm run lint
npm run typecheck
npm test -- --run
npm run build
npm audit --audit-level=high
```

性能脚本：

```bash
docker run --rm -e BASE_URL=http://host.docker.internal:19071 \
  -v "$PWD:/work" grafana/k6:latest \
  run /work/performance/k6-smoke.js
```

实际指标、环境和失败基线见 [PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md)。

## 文档

- [架构](docs/architecture.md)
- [API 与状态机](docs/API.md)
- [使用手册](docs/USAGE.md)
- [端到端验收](docs/E2E.md)
- [部署与运维](DEPLOYMENT.md)
- [安全说明](SECURITY.md)
- [性能报告](PERFORMANCE_REPORT.md)
- [变更日志](CHANGELOG.md)

验收证据位于 `docs/evidence/`；真实浏览器截图和最终报告渲染位于 `output/playwright/final/`，其中 `output/` 为本地验收产物，不纳入版本控制。

## 许可证

代码按 [MIT License](LICENSE) 发布。仓库内演示法规和样例仅用于工程测试，不得作为法律依据。
