# API 与状态机契约

## 基础约定

- Docker 直连后端：`http://127.0.0.1:19071`
- 浏览器同源入口：`http://127.0.0.1:19070/api`
- 除 `GET /api/health` 外均使用 HTTP Basic 认证
- JSON 响应统一为 `{"code":0,"message":"ok","data":...}`
- JSON 错误的 HTTP 状态与 `code` 一致，常见为 `400/401/403/404/409/413/415/500`
- 所有响应包含 `X-Request-Id`；客户端可提交 8-64 位字母、数字和连字符的同名请求头
- SSE 和 DOCX 下载不是 JSON envelope

示例：

```bash
curl -u user@demo.local:demo-change-me \
  http://127.0.0.1:19071/api/auth/me
```

## 角色

| 角色 | 主要能力 |
| --- | --- |
| `USER` | 上传、查看本租户文档、发起/取消审核、处理本人整改 |
| `REVIEWER` | USER 能力 + 风险复核、证据验收、批准、报告 |
| `COMPLIANCE_ADMIN` | REVIEWER 能力 + 指派/关闭/重开整改、审计查看 |
| `SYSTEM_ADMIN` | 跨租户运维读取和管理员操作；跨租户资源访问会审计 |

演示账户见 [../README.md](../README.md)。

## 端点

| 方法与路径 | 最小权限 | 用途 |
| --- | --- | --- |
| `GET /api/health` | 匿名 | 健康、规则包和模型诊断 |
| `GET /api/auth/me` | 登录 | 当前用户、租户、角色和演示标记 |
| `GET /api/auth/assignees` | 合规管理员 | 当前租户可指派用户；系统管理员可指定租户 |
| `GET /api/documents` | 登录 | 按租户列出文档 |
| `POST /api/documents/upload` | USER | 上传 `file`，可带 `docType` |
| `GET /api/documents/{id}` | 登录 | 文档正文与元数据 |
| `GET /api/documents/{id}/sections` | 登录 | 原文分块及位置 |
| `POST /api/documents/{id}/versions` | USER | 上传父文档的新版本 |
| `GET /api/documents/{id}/versions` | 登录 | 文档版本链 |
| `POST /api/reviews/stream/{documentId}` | USER | 发起 SSE 审核 |
| `POST /api/compliance/audit/stream/{documentId}` | USER | 上述端点的兼容别名 |
| `GET /api/reviews` | 登录 | 审核运行列表 |
| `GET /api/reviews/{reviewKey}` | 登录 | 审核、findings、实体、工具、整改和报告详情 |
| `POST /api/reviews/{reviewKey}/cancel` | 发起人或审核员 | 请求取消运行中审核 |
| `POST /api/findings/{findingKey}/review` | 审核员 | `CONFIRM` 或 `FALSE_POSITIVE` |
| `POST /api/remediations` | 合规管理员 | 为已确认风险创建任务 |
| `GET /api/remediations` | 登录 | 列表；普通用户只看到本人任务 |
| `GET /api/remediations/{taskKey}` | 登录 | 任务与证据详情 |
| `POST /api/remediations/{taskKey}/start` | 负责人或管理员 | 开始整改 |
| `POST /api/remediations/{taskKey}/evidence` | 负责人或管理员 | 提交脱敏证据 |
| `POST /api/remediations/{taskKey}/review` | 审核员 | 验收或退回证据 |
| `POST /api/remediations/{taskKey}/close` | 合规管理员 | 关闭已验证任务 |
| `POST /api/remediations/{taskKey}/reopen` | 合规管理员 | 重开已关闭任务 |
| `POST /api/reviews/{reviewKey}/approve` | 审核员 | 批准全部闭环的审核 |
| `GET /api/regulations` | 登录 | DEMO 法规目录 |
| `GET /api/regulations/search` | 登录 | 按 `query/scope/asOf/topK` 检索 |
| `POST /api/reports` | 审核员 | 生成或复用同一快照报告 |
| `GET /api/reports/{reportKey}` | 审核员 | 报告元数据 |
| `GET /api/reports/review/{reviewKey}` | 审核员 | 审核报告版本列表 |
| `GET /api/reports/{reportKey}/download` | 审核员 | 下载 DOCX，响应含 `X-Content-SHA256` |
| `GET /api/audit/events` | 合规管理员 | 审计事件，`limit` 默认为 100 |
| `GET /api/audit/verify` | 合规管理员 | 校验当前租户哈希链；系统管理员必须传 `tenantId` |

`POST /api/compliance/analyze` 是受认证保护的同步兼容入口，适合自动测试，不是浏览器主流程。

## 关键请求

上传：

```bash
curl -u user@demo.local:demo-change-me \
  -F "file=@samples/contract-v2-risky.txt" \
  -F "docType=CONTRACT" \
  http://127.0.0.1:19071/api/documents/upload
```

发起 SSE：

```bash
curl -N -u user@demo.local:demo-change-me \
  -X POST -H "Accept: text/event-stream" \
  http://127.0.0.1:19071/api/reviews/stream/1
```

人工判断：

```json
{
  "decision": "CONFIRM",
  "comment": "命中原文与规则一致，需要整改。"
}
```

创建整改：

```json
{
  "findingKey": "FND-...",
  "assigneeId": "user@demo.local",
  "dueDate": "2026-07-27",
  "description": "删除无限责任表述并补充对等责任上限。"
}
```

提交证据与验收：

```json
{"evidenceText":"脱敏证据：修订稿已将责任上限调整为合同金额。"}
```

```json
{"approved":true,"comment":"证据与整改要求一致。"}
```

## SSE 事件

| 事件 | 关键字段 |
| --- | --- |
| `start` | `reviewId`, `documentId` |
| `stage` | `stage`, `message` |
| `tool` | `name`, `ok`, `code`, `summary` |
| `finding` | 风险、证据、span、页/章/段和状态 |
| `narrative` | `text`，仅工具结果说明 |
| `summary` | `text`, `riskScore`, `riskLevel` |
| `done` | `reviewId`, `summary` |
| `cancelled` | `reviewId` |
| `error` | 通用消息和 `requestId` |

状态机和数据模型见 [architecture.md](architecture.md)。非法或并发冲突转换返回 `409`；重复上传、同 finding 整改和同快照报告分别采用摘要或业务键幂等。
