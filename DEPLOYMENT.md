# Compliance Doc Agent 部署与运维

## 支持环境

- Docker Engine 26+ 或 Docker Desktop，Compose v2
- 推荐至少 2 CPU、4GB 可用内存、2GB 可用磁盘
- 本地开发另需 Java 17+、Maven 3.9+、Node.js 22+
- 已验收的宿主环境为 Windows 11 + Docker Desktop；Compose 配置本身不依赖 Windows 路径

本项目只使用 `19070-19079` 端口。默认前端为 `19070`，后端为 `19071`。

## 推荐启动

在仓库根目录执行：

```bash
# Windows
copy .env.example .env

# Linux / macOS
# cp .env.example .env

docker compose up -d --build
docker compose ps
```

入口：

| 服务 | 地址 |
| --- | --- |
| 工作台 | http://127.0.0.1:19070 |
| 后端健康检查 | http://127.0.0.1:19071/api/health |

`frontend/nginx.conf` 托管生产构建并将 `/api` 反代到后端。SSE 关闭代理缓冲，上传代理上限为 6MB，后端业务上限仍为 5MB。

首次构建完成后运行自动验收：

```bash
python scripts/verify-complete.py \
  --base http://127.0.0.1:19070 \
  --evidence-dir docs/evidence/docker-acceptance
```

成功时脚本退出码为 0，并生成 JSON 证据和实际 DOCX 报告；失败时退出码非 0 并写入失败原因。

停止服务但保留数据：

```bash
docker compose down
```

删除本地演示数据卷：

```bash
docker compose down -v
```

## 本地开发

后端默认监听 `19071`：

```bash
cd backend
mvn spring-boot:run
```

前端默认监听 `19070`，并把 `/api` 代理到 `19071`：

```bash
cd frontend
npm ci
npm run dev
```

如 `19070` 已被 Compose 占用，应先停止 Compose；不要改用允许范围之外的端口。

## 配置

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FRONTEND_PORT` | `19070` | 前端宿主端口 |
| `BACKEND_HOST_PORT` | `19071` | 后端宿主端口 |
| `SPRING_DATASOURCE_URL` | Compose 中的文件型 H2 | named volume 持久化演示数据 |
| `LLM_PROVIDER` | `mock` | 零密钥文本整理模式 |
| `LLM_API_KEY` | 空 | 仅真实 provider 使用 |
| `DEMO_AUTH_ENABLED` | `true` | 本地演示身份；外部部署必须禁用 |
| `DEMO_BCRYPT_STRENGTH` | `8` | 演示启动速度配置，允许范围 8-16 |
| `CORS_ALLOWED_ORIGINS` | 两个本地 `19070` 地址 | 原生后端启动时的允许来源 |

显式设置 `LLM_PROVIDER=openai` 但没有有效密钥时，健康状态会显示不可用，不会静默回退为 Mock。

## 健康与排障

```bash
curl -f http://127.0.0.1:19071/api/health
docker compose logs --tail=200 backend
docker compose logs --tail=100 frontend
```

常见问题：

| 现象 | 判断与处理 |
| --- | --- |
| 前端返回 `502` | 等待后端 `healthy`，再检查后端日志 |
| 上传返回 `413` | 文件超过 5MB；压缩文件或拆分文档后重试 |
| 扫描 PDF 被拒绝 | 当前未集成 OCR，请上传可选择文本的 PDF |
| 登录返回 `401` | 检查演示账户密码与 `DEMO_AUTH_ENABLED` |
| 同文档重复发起返回 `409` | 已有运行中审核，打开现有运行或先取消 |
| 报告下载失败 | 使用审核员以上角色，并确认资源属于当前租户 |

所有 5xx 响应都带 `X-Request-Id`，可用该值关联日志。日志和问题单不得附带原始客户文档、凭据或密钥。

## 外部部署边界

当前 Compose 是可复现的本地发布候选环境，不是无条件生产部署方案。外部部署前至少需要：

- 设置 `DEMO_AUTH_ENABLED=false` 并接入正式身份源；当前演示 Basic Auth 不应经明文 HTTP 暴露
- 使用经过验证的生产数据库、备份恢复、密钥管理、HTTPS、限流和恶意文件扫描
- 获得权威法规数据授权并替换 DEMO 法规集
- 根据数据分级确定文档、报告和审计记录的保留与删除策略

默认 H2 卷适合本地演示和验收，不作为生产数据库可用性结论。
