English | [中文](CHANGELOG-zh.md)

# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to Semantic Versioning.

## [0.1.0] - 2026-06-04

### Added

- New `ailux-provider-mock` module providing a zero-dependency `MockProvider`.
- `MockProvider.generate()` matches `MockRule`s by keyword and falls back gracefully when no keyword is provided.
- `MockProvider.streamGenerate()` emits a deterministic stream of events: `Reasoning* -> Token* -> Usage -> Done`.
- Demo App automatically falls back to `MockProvider` when `ailux.baseUrl` is not configured, so it runs without an API key.
- README adds MockProvider Quick Start, streaming-event examples, and custom-rule documentation.
- Unit tests cover rule matching, fallback behavior, event ordering, reasoning, and streaming token assembly.
- v0.1 demo screenshots and video assets, used by the README and the project site.
- Initial GitHub Actions CI draft, Issue templates, PR template, and contributing guide.
- Maven publishing scaffolding for `ailux-core`, `ailux-api`, `ailux-android`, `ailux-provider-backend`, and `ailux-provider-mock`.

### Changed

- Version roadmap and progress board synced with the actual delivery status of v0.1 MockProvider.
- Demo Chat UI surfaces `UsageInfo`, distinguishing server-reported usage from local estimates.

### Known gaps

- Maven Central coordinates, signing keys, and Central Repository accounts still need to be confirmed by the project owner before publishing.
- v0.1 focuses on MockProvider plus the local Demo loop; a production-ready backend reference implementation is deferred to v0.2.
