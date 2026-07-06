# compliance-doc-agent 部署与运维指南

本文档面向**本地演示**、**Docker Desktop 验证**与**生产化评估**。默认 Mock LLM 模式可在零密钥下完整体验「上传 → 规则命中 → SSE 审核 → 报告」链路。

## 1. 部署形态

| 形态 | 用途 | 说明 |
| --- | --- | --- |
| **Docker Compose dev（推荐）** | 演示 / CI | 后端 + Vite 热更新前端，默认 `LLM_PROVIDER=mock` |
| **Docker Compose prod profile** | 静态前端预览 | Nginx 托管前端 + `/api` 反代，单端口 8080 |
| **本地 Maven + Vite** | 开发调试 | H2 内存库，后端 8090 + 前端 5173 |
| **生产** | 对外服务 | 需 HTTPS、托管 MySQL、真实 LLM 密钥、认证与限流 |

## 2. Docker Compose 快速部署

```bash
cd compliance-doc-agent
cp .env.example .env    # 默认 Mock，无需 LLM_API_KEY
docker compose up -d --build
docker compose ps
curl -f http://127.0.0.1:${BACKEND_HOST_PORT:-8080}/api/health
```

访问：

| 入口 | 地址 |
| --- | --- |
| 后端健康 | http://localhost:8080/api/health |
| 前端（dev） | http://localhost:5173 |

预期健康检查 JSON 含 `"llmProvider":"mock"`。

### 生产静态前端 profile

```bash
docker compose --profile prod up -d --build
```

统一入口：http://localhost:8080（Nginx → 静态资源 + `/api` 反代后端）。

## 3. 本地开发部署

```bash
# 终端 1 — 后端（H2 + Mock，端口 8090）
cd backend && mvn spring-boot:run

# 终端 2 — 前端（Vite proxy → 8090）
cd frontend && npm install && npm run dev
```

验证：

```bash
curl http://localhost:8090/api/health
```

## 4. 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `LLM_PROVIDER` | `mock` | `mock` 零密钥；`openai` 接 OpenAI 兼容网关 |
| `LLM_API_KEY` | （空） | 留空时使用 Mock |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | 兼容网关地址 |
| `LLM_MODEL` | `gpt-4o-mini` | 模型名 |
| `SPRING_DATASOURCE_URL` | H2 内存 | Docker 默认；生产换 MySQL JDBC |
| `BACKEND_HOST_PORT` | `8080` | Compose 后端宿主端口 |
| `FRONTEND_PORT` | `5173` / `8080` | dev 5173；prod profile 8080 |

完整说明见 [.env.example](.env.example) 与 [docs/USAGE.md](docs/USAGE.md)。

## 5. 健康检查与 smoke

```bash
# 健康
curl -f http://127.0.0.1:8080/api/health

# 文档列表（空库返回 []）
curl -f http://127.0.0.1:8080/api/documents

# 上传样例 + SSE 审核（记录返回 id 替换 {id}）
curl -F "file=@backend/src/main/resources/samples/合同条款片段.txt" \
     -F "docType=CONTRACT" \
     http://127.0.0.1:8080/api/documents/upload

curl -N -X POST -H "Accept: text/event-stream" \
     http://127.0.0.1:8080/api/compliance/audit/stream/{id}
```

压测脚本见 [performance/k6-smoke.js](performance/k6-smoke.js) 与 [PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md)。

## 6. 反向代理（HTTPS）

生产建议在网关层终止 TLS 并限制来源 IP / 速率。SSE 端点 `/api/compliance/audit/stream/{docId}` 需：

- 关闭响应缓冲（`proxy_buffering off`）
- 适当延长 read timeout（Mock LLM 流式约 30–90s）

prod profile 的 `frontend/nginx.conf` 已反代 `/api` → `backend:8080`。

## 7. 升级与回滚

```bash
git pull
docker compose up -d --build
cd backend && mvn -B test
```

回滚：

1. 镜像使用 Git SHA 标签。
2. 使用 MySQL 时发布前备份卷或逻辑备份。
3. `docker compose down` 后回退到上一版本镜像并 `up -d`。

## 8. 运维 Runbook

| 现象 | 处理 |
| --- | --- |
| 前端无法连后端 | 确认端口：本地 dev 8090，Docker 8080；检查 `VITE_PROXY_TARGET` |
| SSE 无输出 | 检查 `Accept: text/event-stream`；查看后端日志与 `documentId` 是否存在 |
| 规则未命中 | 使用 `samples/合同条款片段.txt` 验证；见 USAGE.md 预期规则表 |
| 切换真实 LLM 失败 | 确认 `LLM_PROVIDER=openai` 与有效 `LLM_API_KEY`；检查网关 URL |

## 9. 相关文档

| 文档 | 说明 |
| --- | --- |
| [README.md](README.md) | 项目概览与演示流程 |
| [docs/USAGE.md](docs/USAGE.md) | Mock 零密钥详细体验路径 |
| [SECURITY.md](SECURITY.md) | 安全策略与漏洞报告 |
| [PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md) | 压测目标与脚本 |
