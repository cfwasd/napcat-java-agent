# 内部架构

本文档面向框架开发者，描述各模块职责、核心流程和扩展点。

---

## 一、模块职责

### dingdong-core

- **协议层**：OneBot11 事件/消息数据模型、JSON 反序列化（Jackson）
- **通信层**：`BotAdapter` 抽象及四种实现（WS/HTTP）
- **路由层**：`HandlerRegistry` 注解扫描、接口收集、路由表构建、事件分发
- **API 层**：`NapCatApi` 封装所有 OneBot11 + NapCat 扩展 API
- **消息解析**：支持 array/string（CQ 码）双格式，未知类型降级为 `UnknownSegment`
- **渠道抽象**：`ChannelEvent` / `ChannelMessageEvent` 接口定义

### dingdong-channel-api

- 渠道抽象 API 定义：`BotChannel`、`ChannelEvent`、`MessageSender`
- 不依赖具体渠道实现，供 Bot 层统一调用

### dingdong-qqofficial

- **QQ 官方 API v2** 渠道实现：WebSocket 连接、Token 管理、消息收发
- **Markdown + 键盘按钮**支持：`replyWithKeyboard()` 发送 Markdown 消息带按钮面板
- **OpenID 映射**：将 QQ 开放平台的 OpenID 映射为稳定 long ID
- **自动重连**：Token 刷新触发 WebSocket 自动重连（指数退避）

### dingdong-agent

- **Agent 引擎**：`NapCatAgent` 驱动 ReAct 循环，支持多模态图片提取
- **会话管理**：`SessionManager` 按 `SessionKey(userId, groupId)` 隔离上下文
- **Tool 注册**：`ToolRegistry` 扫描 `@Tool` 注解，生成 LLM JSON Schema
- **持久化记忆**：`MemoryStore` / `MemoryExtractor` / `DailyMemorySummarizer`
- **定时任务**：`ScheduleTool` / `TaskExecutor` / `SchedulePoller` / `TimerWheel`

### dingdong-cultivation

- **修仙系统**：境界突破、修炼、渡劫
- **宗门系统**：创建/加入/退出宗门、宗门捐献、宗门排行
- **丹药系统**：丹药商店、购买丹药
- **婚姻系统**：求婚、结婚、离婚、双修
- **签到系统**：每日签到（修仙者额外修为）
- **运势系统**：每日运势占卜

### dingdong-llm-providers

- **dingdong-llm-openai**：OpenAI 协议兼容（含 vision、reasoning_content、tool_calls）
- **dingdong-llm-anthropic**：Anthropic Claude Messages API
- **dingdong-llm-ollama**：Ollama HTTP API（`/api/chat`，stream=false）

### dingdong-boot-starter

- **自动配置**：`DingDongAutoConfiguration` 根据 `application.yml` 装配 Bean
- **属性绑定**：`BotProperties` / `NapCatProperties` 等
- **Bean 扫描**：`DingDongBeanPostProcessor` 自动注册注解方法
- **生命周期**：`DingDongLifecycle` 启动 Adapter，关闭时优雅停止

### dingdong-admin

示例应用，演示注解方式、接口方式、Agent 模式、修仙系统、QQ 官方面板的完整用法。

---

## 二、核心流程

### 2.1 启动流程

```
Spring ApplicationContext 初始化
  │
  ├─ DingDongAutoConfiguration
  │     ├─ 解析配置属性
  │     ├─ 创建 BotAdapter（根据 adapter.type）
  │     ├─ 创建 MessageRouter / EventDispatcher / HandlerRegistry
  │     ├─ 如 agent.enabled=true：创建 ToolRegistry / SessionManager / NapCatAgent
  │     └─ 注册 DingDongLifecycle
  │
  ├─ DingDongBeanPostProcessor
  │     ├─ 扫描 @Component Bean 的方法注解
  │     └─ 注册 HandlerMethod 到 HandlerRegistry
  │
  └─ DingDongLifecycle.start()
        ├─ 启动 QQ 官方渠道（如启用）
        ├─ 启动 SchedulePoller（如启用）
        ├─ 启动会话过期清理线程
        └─ 启动所有 BotAdapter
```

### 2.2 事件处理流程

```
NapCat 上报事件（JSON）
  │
  ▼
BotAdapter 收到原始 JSON → MessageRouter 区分事件与 API 响应
  │
  ▼ 事件分支
EventDispatcher.dispatch(event)
  ├─ 注入 api 到 MessageEvent
  ├─ 忽略自身消息
  ├─ 查找 HandlerRegistry 中匹配的 Handlers
  ├─ 顺序执行 Handlers
  │     命令匹配 → 注解 handler → 接口 handler → fallback
  ├─ 处理返回值（String/MessageChain → 自动回复）
  └─ 清理 EventContext
```

### 2.3 ReAct Agent 流程

```
NapCatAgent.chat(SessionKey, input, config)
  │
  ├─ 获取/创建 Session，新会话时注入 system prompt + 记忆
  ├─ 提取输入中的 [图片:url] → imageUrls
  │
  ▼
Round 1..N（N <= maxRounds）
  ├─ 调用 LlmProvider.chat(session, null, tools)
  ├─ 纯文本 → 直接返回
  └─ ToolCall → 执行工具 → 收集结果 → 进入下一轮
  │
  ▼
完成后 → MemoryExtractor 异步提取记忆
```

---

## 三、核心类图

### 事件体系

```
OB11Event
  ├─ MessageEvent
  │   ├─ GroupMessageEvent
  │   └─ PrivateMessageEvent
  ├─ NoticeEvent（群通知）
  ├─ RequestEvent（好友/群请求）
  └─ MetaEvent（生命周期/心跳）

ChannelEvent (interface)
  └─ ChannelMessageEvent (interface)  ← QQ 官方渠道
```

### Agent 体系

```
NapCatAgent → LlmProvider → ToolRegistry → SessionManager → MemoryStore
                                                    ↓
                                             SchedulePoller → TimerWheel
```

---

## 四、扩展点

### 自定义通信适配器

实现 `BotAdapter` 接口，注册为 Spring Bean。

### 自定义 LLM Provider

实现 `LlmProvider` 接口。

### 自定义渠道

实现 `BotChannel` 接口，注册为 Spring Bean，框架自动汇入事件路由。

---

## 五、线程模型

| 线程 | 用途 |
|------|------|
| `dingdong-event-pool` | 事件处理线程池（默认 4-16 线程） |
| `dingdong-poller` | 定时任务轮询守护线程（每 5 分钟） |
| `dingdong-timer` | 定时任务执行线程池（2 线程） |
| `dingdong-daily-summary` | 每日记忆归纳线程池（4 线程） |
| `dingdong-api-cleaner` | API 超时清理线程（每 10 秒） |
| `qq-official-reconnect` | QQ 官方 WS 重连线程 |
