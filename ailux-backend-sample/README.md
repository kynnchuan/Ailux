# Ailux Backend Sample

Ailux SDK 的参考后端实现，基于 Spring Boot，用于验证完整生产链路：

```
Android App → Ailux SDK → 后端代理 → LLM API → SSE → UI
```

## 功能

- **SSE 流式转发** — OkHttp 调 LLM API，SseEmitter 转发给客户端
- **服务端 Function Calling** — 订单查询助手（query_orders / get_order_detail / get_logistics）
- **客户端 FC 透传** — 不在服务端注册表中的 tool_call 直接转发给客户端
- **Parser 协商** — 响应头 `X-Ailux-Parser` 标识协议格式
- **模型路由** — 支持 OpenAI + DeepSeek，按 provider 字段路由
- **鉴权** — 预置 Token 方式，三种账号（free/pro/admin）
- **配额** — 每日请求次数 + Token 上限 + 可用模型范围
- **上下文管理** — 服务端存储对话历史 + 滑动窗口裁剪
- **连接断开取消** — 客户端断开时停止 LLM 请求

## 快速开始

```bash
# 设置 API Key
export OPENAI_API_KEY=sk-xxx
export DEEPSEEK_API_KEY=sk-xxx

# 运行
cd ailux-backend-sample
./gradlew bootRun
```

服务启动后访问 http://localhost:8080

## 预置账号

| 账号 | Token | 默认模型 | 每日请求 |
|------|-------|---------|---------|
| free_user | `token-free-001` | deepseek-chat | 20 |
| pro_user | `token-pro-001` | gpt-4o | 100 |
| admin | `token-admin-001` | gpt-4o | 无限 |

## API 接口

- `POST /api/chat/completions` — 流式对话
- `GET /api/models` — 查询可用模型
- `GET /api/quota` — 查询配额用量

## 技术栈

- Java 17 + Spring Boot 3.3
- Spring MVC + SseEmitter
- OkHttp (调 LLM API)
- H2 内嵌数据库 + Spring Data JPA
- Jackson JSON

## 技术方案

详见 `ailux-docs/specs/v0.2/v0.2.2-backend-sample.md`
