[English](CONTRIBUTING.md) | 中文

# Contributing to Ailux

感谢你愿意参与 Ailux。这个项目目前处于早期阶段，优先目标是把 Android LLM SDK 的核心抽象、流式事件和零依赖开发体验打磨稳定。

## 开发环境

- macOS / Linux / Windows 均可，推荐 macOS 或 Linux。
- JDK 17。
- Android Studio 最新稳定版。
- Gradle 使用仓库内 wrapper：`./gradlew`。

## 本地验证

提交前建议至少运行：

```bash
./gradlew :ailux-provider-mock:testDebugUnitTest
./gradlew :ailux-provider-mock:compileDebugKotlin
```

如果本机默认 Gradle 缓存权限异常，可以临时使用项目内缓存：

```bash
GRADLE_USER_HOME=.gradle-workbuddy ./gradlew :ailux-provider-mock:testDebugUnitTest
GRADLE_USER_HOME=.gradle-workbuddy ./gradlew :ailux-provider-mock:compileDebugKotlin
```

## 分支与提交

- 功能分支：`feat/<short-name>`
- 修复分支：`fix/<short-name>`
- 文档分支：`docs/<short-name>`

提交信息建议使用 Conventional Commits：

```text
feat(provider-mock): stream mock responses as token events
fix(app): render usage info after stream completion
docs(readme): add v0.1 demo screenshots
```

## PR 要求

请在 PR 中说明：

1. 这次改了什么。
2. 为什么需要改。
3. 如何验证。
4. 是否影响 API / 文档 / Demo 截图。

如果改动涉及 `LLMEvent`、`LLMProvider`、`AiluxClient` 等核心契约，请同步更新 README、站点文档和版本进度看板。

## 隐私与安全边界

- 不要提交真实 API Key、后端地址、用户 Prompt、模型 Response 明文日志。
- Demo 中的 BYOK / debug 直连仅用于本地验证，不作为生产推荐路径。
- Issue 与 PR 中如果包含诊断信息，请先脱敏。

## 代码风格

- Kotlin 使用官方风格。
- 对外 API 优先保持小而稳定，避免为了示例便利污染核心契约。
- MockProvider 必须保持确定性、零网络、可重复测试。
