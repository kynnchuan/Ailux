English | [中文](CONTRIBUTING-zh.md)

# Contributing to Ailux

Thanks for your interest in contributing to Ailux. The project is still in its early days; the priority is to stabilize the Android LLM SDK's core abstractions, streaming events, and zero-dependency developer experience.

## Development environment

- macOS / Linux / Windows are all supported. macOS or Linux is recommended.
- JDK 17.
- Latest stable Android Studio.
- Use the Gradle wrapper bundled in the repo: `./gradlew`.

## Local verification

Before submitting a change, please run at least:

```bash
./gradlew :ailux-provider-mock:testDebugUnitTest
./gradlew :ailux-provider-mock:compileDebugKotlin
```

If your machine's default Gradle cache has permission issues, you can use the in-repo cache as a temporary workaround:

```bash
GRADLE_USER_HOME=.gradle-workbuddy ./gradlew :ailux-provider-mock:testDebugUnitTest
GRADLE_USER_HOME=.gradle-workbuddy ./gradlew :ailux-provider-mock:compileDebugKotlin
```

## Branches and commits

- Feature branches: `feat/<short-name>`
- Bugfix branches: `fix/<short-name>`
- Documentation branches: `docs/<short-name>`

Commit messages follow the Conventional Commits convention:

```text
feat(provider-mock): stream mock responses as token events
fix(app): render usage info after stream completion
docs(readme): add v0.1 demo screenshots
```

## Pull request expectations

Please describe in the PR:

1. What changed.
2. Why the change is needed.
3. How it was verified.
4. Whether it affects the public API, documentation, or demo screenshots.

If your change touches core contracts such as `LLMEvent`, `LLMProvider`, or `AiluxClient`, please also update the README, the project site documentation, and the version progress board.

## Privacy and security boundaries

- Do not commit real API keys, backend endpoints, raw user prompts, or raw model responses.
- BYOK / debug direct-call mode in the Demo is for local verification only, not a production-recommended path.
- If you include diagnostic information in Issues or PRs, please scrub sensitive data first.

## Code style

- Use official Kotlin coding conventions.
- Keep the public API small and stable; do not pollute core contracts for the sake of demo convenience.
- MockProvider must remain deterministic, network-free, and reproducibly testable.
