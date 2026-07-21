# E2E 端到端验收

## 自动验收

推荐从空演示卷开始。`down -v` 会删除本项目本地演示数据，只在确认不需要保留时使用。

```bash
docker compose down -v
docker compose up -d --build
python scripts/verify-complete.py \
  --base http://127.0.0.1:19070 \
  --evidence-dir docs/evidence/docker-acceptance
```

脚本成功退出码为 0，失败退出码非 0。成功结果写入：

- `docs/evidence/docker-acceptance/acceptance-latest.json`
- `docs/evidence/docker-acceptance/acceptance-report.docx`

覆盖内容：

| 场景 | 断言 |
| --- | --- |
| 健康和身份 | Mock、规则包、USER 租户正确 |
| 错误密码 | HTTP 401 |
| 重复上传 | 返回原文档和 `duplicate=true` |
| 5 个脱敏案例 | 预期规则、法规引用、实体和工具轨迹一致 |
| Prompt injection | 正文命令不进入模型叙述 |
| 原文定位 | 所有带 span 的风险能映射到 chunk |
| 法规零命中 | 返回空数组，不填充无关条目 |
| 跨租户读取 | HTTP 403 |
| 普通用户读审计 | HTTP 403 |
| 重复运行与取消 | 活动运行冲突 409，取消落到 `CANCELLED` |
| 人工闭环 | 确认、指派、证据、验收、关闭、复审、批准 |
| 报告 | 有效 DOCX、哈希一致、同快照幂等 |
| 审计 | 租户 SHA-256 链校验有效 |

固定演示集的 precision/recall 仅用于证明规则期望可重复，不应描述为法律准确率。

## 真实浏览器流程

使用 `375x812`、`768x1024` 和 `1440x900` 三档视口逐步执行：

1. 业务用户登录，上传合同并发起审核。
2. 检查进度、取消反馈、工具轨迹、原文高亮、法规引用和实体定位。
3. 审核员先标记一条误报，再确认需要整改的风险并填写意见。
4. 合规管理员创建整改任务。
5. 业务用户开始任务并提交脱敏证据。
6. 审核员验收证据，合规管理员关闭任务。
7. 审核员复审批准、生成并下载报告。
8. 合规管理员查看审计事件并校验哈希链。
9. 各视口检查横向滚动、文本溢出、焦点、抽屉/弹窗关闭和控制台。

本轮证据位于 `output/playwright/final/`：

- `screenshots/review-desktop-1440x900.png`
- `screenshots/review-tablet-768x1024.png`
- `screenshots/review-mobile-375x812.png`
- `screenshots/audit-task-chain.png`
- `browser-e2e-contract-合规审核报告-v3.docx`
- `browser-report-render-v3/page-1.png` 与 `page-2.png`

`output/` 是本地可复现验收产物并被 Git 忽略；长期证据摘要存放在 `docs/evidence/`。

## 报告渲染验收

报告必须同时检查：

- ZIP/DOCX 容器有效且文本可提取
- 页面包含标题、版本、风险、证据、法规引用、人工意见和整改状态
- 中文字体可读，风险等级为中文
- 表格不越界，标题不孤页，页尾不截断正文
- 下载响应 `X-Content-SHA256` 与元数据一致

已保存的最终浏览器报告为 2 页。Docker 自动验收报告也已转换为 2 页 PDF/PNG 并逐页查看，中文、表格、页码和分页均无裁切、重叠或缺字：

- 渲染目录：`docs/evidence/docker-acceptance/report-render-final/`
- DOCX SHA-256：`dca50fbf046cc4705dd1d7ba5ae1b629e6c6b62336bd0665cc9143bfe4c84dd6`
- PDF SHA-256：`ecbfcb22b44cfee6cf424fd6d4b53713342b6688b7f0b2280905872713e0fdae`

## 最终质量门禁

```bash
cd backend
mvn -B clean test

cd ../frontend
npm run lint
npm run typecheck
npm test -- --run
npm run build
npm audit --audit-level=high

cd ..
python scripts/verify-complete.py \
  --base http://127.0.0.1:19070 \
  --evidence-dir docs/evidence/docker-acceptance
git diff --check
```

本轮最终门禁已检查 secret scan、仅使用 `19070-19079`、无 Stub 类、浏览器控制台无错误，并审阅了最终 `git status --short`。所有检查均通过后，才将本仓库标记为发布候选验收完成。
