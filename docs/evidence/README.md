# 验收证据说明

本目录只保存可复核的摘要、机器报告和小型报告样例。浏览器会话、临时截图和大体积渲染中间件位于被 Git 忽略的 `output/`。

## 核心证据

| 路径 | 内容 |
| --- | --- |
| `docker-acceptance/acceptance-latest.json` | 从 Compose 入口执行的完整自动验收 |
| `docker-acceptance/acceptance-report.docx` | 自动闭环生成并下载的实际报告 |
| `docker-acceptance/report-render-final/` | 最新 Docker 报告 PDF 和两页 PNG 渲染 |
| `acceptance-latest.json` | 原生服务验收摘要 |
| `acceptance-report.docx` | 原生服务生成的报告样例 |
| `report-render/` | 原生报告 PDF/逐页 PNG 渲染 |
| `lighthouse-mobile.json` | 生产构建移动 Lighthouse |
| `lighthouse-desktop.json` | 生产构建桌面 Lighthouse |
| `lighthouse-vite-dev-baseline.json` | 开发服务器性能基线，不作发布结论 |
| `performance-k6.json` | 优化后 20 VU 读压测 |
| `performance-k6-write.json` | 实际新增上传写压测 |

`docker-acceptance/acceptance-nginx-1mb-failure.json`、`acceptance-relative-path-failure.json`、`performance-k6-10vu-before.json`、`performance-k6-20vu-overload.json` 和 `performance-k6-write-duplicate-check-failure.json` 是保留的失败或优化前基线，用于证明问题和修复过程，不代表最终状态。

最新 Docker 验收报告 SHA-256 为 `dca50fbf046cc4705dd1d7ba5ae1b629e6c6b62336bd0665cc9143bfe4c84dd6`，与 `acceptance-latest.json` 中的下载响应摘要一致；渲染 PDF SHA-256 为 `ecbfcb22b44cfee6cf424fd6d4b53713342b6688b7f0b2280905872713e0fdae`。

## 重新生成

```bash
docker compose up -d --build
python scripts/verify-complete.py \
  --base http://127.0.0.1:19070 \
  --evidence-dir docs/evidence/docker-acceptance
```

读写性能命令见 [../../PERFORMANCE_REPORT.md](../../PERFORMANCE_REPORT.md)，完整 E2E 和报告检查见 [../E2E.md](../E2E.md)。

任何评估数字都必须同时说明样例范围。不得把 DEMO 规则集结果、Mock 文本或本地性能外推为法律准确率、生产容量或法定认证。
