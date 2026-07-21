# Changelog

本项目采用 Keep a Changelog 结构，当前版本保持 `0.1.0`，以下内容为 2026-07-20 发布候选收敛结果，尚未创建 Git 标签或发布。

## [Unreleased]

### Added

- TXT、Markdown、文本型 PDF、DOCX 解析及页/章/段/span 原文定位
- 文件签名、5MB、加密/扫描 PDF、DOCX ZIP 和二进制伪装防护
- 版本化规则包、演示法规检索、条款版本比较、实体抽取和风险汇总
- 8 个带 schema、权限、超时、错误契约和执行审计的真实 Agent 工具
- 四角色 RBAC、租户隔离、人工复核、整改证据、复审、关闭/重开状态机
- SHA-256 前向哈希审计链与跨租户系统管理员读审计
- 快照化、版本化、幂等的 DOCX 报告和下载哈希
- 文档、审核、整改、演示法规、审计工作台及响应式移动布局
- 脱敏合同、隐私、内部制度、版本差异和 prompt injection 评估集
- 一键 Docker Compose、自动完整验收、读写 k6 和 Lighthouse 证据

### Changed

- Mock LLM 只整理真实工具结果，不再生成固定 findings
- 服务端口统一为 `19070` 和 `19071`
- API 错误使用结构化 4xx/5xx，所有响应带 `X-Request-Id`
- 默认 H2 改为文件持久化，H2 Console 默认关闭，CORS 收紧为本地工作台来源

### Fixed

- 修复跨文档类型规则误报、重复审核、取消恢复和非法状态转换
- 修复报告中文等级、分页孤行、快照漂移和重复生成
- 修复移动端表格横向溢出、Nginx 1MB 上传限制和 Compose 端口冲突

## [0.1.0] - 2026-07-04

### Added

- 初始 MVP 规格、README、规则引擎骨架、SSE 演示和版本文件
