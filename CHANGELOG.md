# Changelog

本项目的所有重要变更均记录在此文件。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.0] - 2026-07-04

### Added

- **项目规格书**：`docs/ai-portfolio/project-07-spec.md`，定义规则引擎 + LLM 双层审核架构、8 个 Function Calling 工具、审核工作流与数据模型
- **README 骨架**：项目定位、架构图、技术栈、目录结构规划与 Roadmap
- **VERSION / CHANGELOG**：版本号 0.1.0，建立变更日志规范

### Planned（待实现）

- 文档上传与 PDF/Word 解析分块
- YAML 规则 DSL 引擎 + 内置合同规则包
- Mock LLM 语义审查 + Function Calling Agent 编排
- 审核工作台（原文高亮 + 发现项 + SSE 流式对话）
- Docker Compose 一键部署（H2 / MySQL + Mock LLM）

[0.1.0]: https://github.com/your-org/compliance-doc-agent/releases/tag/v0.1.0
