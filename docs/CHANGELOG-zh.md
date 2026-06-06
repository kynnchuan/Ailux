[English](CHANGELOG.md) | 中文

# Changelog

所有重要变更都会记录在这里。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [0.1.0] - 2026-06-04

### Added

- 新增 `ailux-provider-mock` 模块，提供零依赖 `MockProvider`。
- `MockProvider.generate()` 支持按关键词命中 `MockRule`，并提供空 keyword fallback。
- `MockProvider.streamGenerate()` 支持确定性的流式事件：`Reasoning* -> Token* -> Usage -> Done`。
- Demo App 在未配置 `ailux.baseUrl` 时自动回退到 `MockProvider`，无需 API Key 即可运行。
- README 增加 MockProvider Quick Start、流式事件示例和自定义规则说明。
- 新增单元测试覆盖规则命中、fallback、事件顺序、reasoning 与流式拼接。
- 新增 v0.1 Demo 截图与视频资源，供 README 和站点展示。
- 新增 GitHub Actions CI 草案、Issue 模板、PR 模板与贡献指南。
- 新增 Maven 发布配置草案，覆盖 `ailux-core`、`ailux-api`、`ailux-android`、`ailux-provider-backend`、`ailux-provider-mock`。

### Changed

- 版本路线图与进度看板同步 v0.1 MockProvider 的真实完成状态。
- Demo Chat UI 展示 `UsageInfo`，区分服务端用量与本地估算用量。

### Known gaps

- Maven Central 坐标、签名密钥、中央仓库账号仍需发布前由项目所有者确认。
- v0.1 当前以 MockProvider 和本地 Demo 闭环为主，真实生产级后端样板继续放入 v0.2。
