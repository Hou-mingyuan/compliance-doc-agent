# 使用手册

## 启动与登录

```bash
docker compose up -d --build
```

打开 `http://127.0.0.1:19070`。默认密码：

- 业务用户、审核员、合规管理员：`demo-change-me`
- 系统管理员：`admin-change-me`

登录页可直接选择四个常用演示角色。所有账户只用于本地脱敏环境。

## 3-5 分钟演示流程

1. 以业务用户登录，在“文档”上传 `samples/contract-v2-risky.txt`，类型选“合同”。
2. 点击“发起审核”。工作台会显示阶段、真实工具轨迹、原文高亮、法规引用和实体 span。
3. 退出后以审核员登录，打开该审核，填写意见并确认风险；也可把不成立项标为误报。
4. 以合规管理员登录，在风险上创建整改任务，负责人选择 `user@demo.local`。
5. 业务用户在“整改”开始任务并提交脱敏证据。
6. 审核员验收证据；合规管理员关闭任务。
7. 审核员复审批准，生成并下载 DOCX 报告。
8. 合规管理员打开“审计”，重新校验租户哈希链。

页面刷新后数据从后端恢复；切换角色会重新按服务端权限加载资源。

## 样例集

| 文件 | 用途 |
| --- | --- |
| `contract-v1-safe.txt` | 安全合同基线、重复上传 |
| `contract-v2-risky.txt` | 合同版本差异、无限责任命中 |
| `privacy-demo.md` | 隐私最小化与证件脱敏 |
| `policy-demo.md` | 内部采购制度规则和内规引用 |
| `prompt-injection-demo.txt` | 验证正文命令不被执行 |
| `expected-results.json` | 固定演示集的预期规则、引用和实体 |

样例 precision/recall 只衡量仓库内规则与 DEMO 目录，不是法律准确率。

## 文档限制

支持 TXT、Markdown、可选择文本的 PDF 和 DOCX，单文件最大 5MB。

以下情况会明确失败并保留可操作错误：

- 扫描或无文本 PDF：当前没有 OCR
- 加密 PDF
- 损坏或疑似 ZIP bomb 的 DOCX
- 扩展名与签名不一致
- 二进制内容伪装为 TXT/Markdown
- 同文档已有运行中审核

重复上传同一租户内相同内容会返回原文档并标记 `duplicate=true`，不会重复写入。

## 工作流规则

- 审核员必须为每条 `OPEN` 风险填写意见并判断误报或确认。
- 只有确认风险可以创建整改；同一 finding 重复创建返回原任务。
- 只有负责人或合规管理员可以开始任务和提交证据。
- 只有审核员以上可以验收；未通过会进入 `REJECTED`，可再次处理。
- 合规管理员只能关闭 `VERIFIED` 任务。
- 仍有开放、确认或待整改风险时，服务端拒绝批准审核。
- 报告基于审核快照；同一快照重复生成返回同一报告，数据变化后产生新版本。

## Mock 与真实模型

默认 `LLM_PROVIDER=mock`，无需密钥。Mock 只生成可预测的说明文字，所有风险、引用、实体和分数来自确定性工具。

配置 OpenAI-compatible provider 时需要设置 `LLM_PROVIDER=openai`、`LLM_BASE_URL`、`LLM_MODEL` 和 `LLM_API_KEY`。健康接口会显示 provider 是否就绪；配置错误不会回退为 Mock。

无论使用哪种 provider，模型都不能覆盖服务端文档、租户、操作者或持久化风险。

## 自动验收

```bash
python scripts/verify-complete.py \
  --base http://127.0.0.1:19070 \
  --evidence-dir docs/evidence/docker-acceptance
```

该命令覆盖样例评估、重复、取消、401、403、零法规命中、人工复核、整改、证据、复审、报告下载和审计链。详细步骤见 [E2E.md](E2E.md)，接口见 [API.md](API.md)。

## 使用边界

演示法规不是权威数据，报告供具备职责的人员复核，不替代律师意见或法定认证。上传真实材料前必须先建立正式身份、加密、恶意文件扫描、数据保留和法规授权方案。
