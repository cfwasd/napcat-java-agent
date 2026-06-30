# Agent 使用指南

本文档描述框架的 AI Agent 功能，包括 ReAct 循环、Tool 注册、LLM 对接、多模态、会话管理和记忆系统。

---

## 一、Agent 概述

框架内置轻量级 ReAct Agent，核心能力：

- **多轮思考**：收到用户消息后，Agent 可以多次调用 LLM，每轮决定直接回复或调用工具
- **工具调用**：自动将 `@Tool` 标记的方法转换为 LLM 的 Function Calling Schema
- **会话隔离**：按 `userId + groupId` 复合键隔离会话上下文，支持过期清理
- **多 LLM 后端**：OpenAI（含多模态/vision）、Claude、Ollama、自定义 OpenAI 端点
- **持久化记忆**：跨会话自动记住用户关键信息，新会话自动注入上下文
- **定时任务**：Agent 可为用户创建 Cron 定时任务，自动推送消息
- **LLM 备用模型**：主模型失败时自动切换
- **多人格系统**：内置 5 种人格，可自由增删改，支持 TTS 声线映射

---

## 二、启用 Agent

### 基础配置

```yaml
dingdong:
  agent:
    enabled: true
    max-react-rounds: 5
    session-ttl: 7200
    enable-vision: true
    skills-path: skills
  llm:
    provider: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o
```

### 触发方式

**方式一：被 @ 时自动触发**

```yaml
dingdong:
  qq:
    bot:
      at-me-trigger: true  # 无需写 Handler
```

**方式二：手动调用**

```java
@Component
public class AgentBot {
    @Autowired
    private NapCatAgent agent;

    @OnGroupMessage
    @MentionFilter
    public void onAt(GroupMessageEvent event) {
        String prompt = event.getMessage().toAgentPrompt();
        agent.chat(event.getUserId(), event.getGroupId(), prompt)
            .thenAccept(event::reply);
    }
}
```

**方式三：命令触发**

```java
@OnGroupMessage
@Command("/ai {prompt}")
public void aiCommand(GroupMessageEvent event, @Param("prompt") String prompt) {
    agent.chat(event.getUserId(), event.getGroupId(), prompt)
        .thenAccept(event::reply);
}
```

### 自定义配置

```java
AgentConfig config = AgentConfig.builder()
    .maxRounds(3)
    .systemPrompt("你是专业客服")
    .timeoutPerRound(10000)
    .showToolProcess(true)
    .ackCallback(() -> event.reply(MessageChain.ofFace(277)))
    .build();

agent.chat(event.getUserId(), event.getGroupId(), "问题", config,
    toolMsg -> event.reply(toolMsg))
    .thenAccept(event::reply);
```

---

## 三、注册工具（Tool）

工具是让 Agent 具备外部能力的关键。框架自动扫描 `@Tool` 注解的方法。

```java
@Component
public class CalculatorTool {

    @Tool(name = "calculate", description = "执行数学计算")
    public String calculate(
        @ToolParam(description = "数学表达式", required = true) String expression
    ) {
        // 实现计算逻辑
    }
}

@Component
public class WeatherTool {
    @Autowired
    private WeatherService weatherService;

    @Tool(name = "get_weather", description = "查询指定城市的当前天气")
    public String getWeather(
        @ToolParam(description = "城市名称", required = true) String city
    ) {
        return weatherService.query(city);
    }
}
```

### 内置工具

| 工具名 | 功能 | 说明 |
|--------|------|------|
| `web_search` | 联网搜索 | 可配置搜索引擎实例 |
| `fetch_url` | 抓取网页 | HTTP 获取指定 URL 文本内容 |
| `get_current_time` | 日期时间 | 获取当前时间、计算日期间隔 |
| `create_schedule` | 创建定时任务 | 创建 Cron 定时任务 |
| `delete_schedule` | 删除定时任务 | 按 ID 或名称删除 |
| `list_schedules` | 列出任务 | 查看所有定时任务 |
| `toggle_schedule` | 启停任务 | 启用或禁用指定任务 |
| `text_to_image` | 文生图 | 通过 DALL·E / 兼容 API 生成图片 |
| `text_to_speech` | 语音合成 | Edge TTS 语音生成 |

---

## 四、多模态支持

当消息包含图片时，`MessageChain.toAgentPrompt()` 会生成 `[图片:url]` 格式标记，`NapCatAgent` 自动提取为 `image_url` 发送给 LLM。

**要求：**
- 仅 OpenAI Provider 支持多模态
- 模型需支持 vision（如 `gpt-4o`、`qwen-vl` 等）
- 图片地址支持 HTTP/HTTPS 和 base64 data URL

---

## 五、会话管理

- 按 `SessionKey(userId, groupId)` 隔离会话，私聊时 `groupId = 0`
- 同一用户在不同群聊的会话完全隔离
- 默认 TTL 7200 秒，每 30 分钟自动清理过期会话
- 默认最大历史消息 40 条，超出时自动截断

```java
@Autowired
private SessionManager sessionManager;

// 清除某用户的会话
sessionManager.clear(new SessionKey(userId, groupId));
sessionManager.clear(SessionKey.ofPrivate(userId));
```

---

## 六、人格系统

框架内置 5 种人格，通过配置文件可自由增删改：

```yaml
dingdong:
  agent:
    personas:
      - id: default
        name: 小助手
        description: 默认人格，随和的群友
        voice-profile: default-voice
        systemPrompt: |
          你是群里的一个老群友...
      - id: tsundere
        name: 傲娇
        description: 嘴硬心软的傲娇角色
        ...
      - id: scholar
        name: 学者
        ...
      - id: funny
        name: 逗比
        ...
      - id: gentle
        name: 知心姐姐
        ...
```

群友可通过 `/persona` 命令查看和切换人格。

---

## 七、TTS 语音模式

集成 Edge TTS，支持人格声线映射：

```yaml
dingdong:
  agent:
    tts:
      enabled: true
      model: "edge-tts"
      default-voice: "zh-CN-XiaoyiNeural"
      voice-profiles:
        default-voice:
          voice: "zh-CN-YunxiNeural"
          speed: 1.0
        tsundere-voice:
          voice: "zh-CN-XiaoxiaoNeural"
          speed: 1.1
```

三种模式通过 `/voice` 命令切换：默认（50% 概率语音） / 纯文字 / 纯语音。

---

## 八、持久化长期记忆

### 工作原理

```
对话中 → MemoryExtractor 异步提取 → memories 表（fact/preference/topic）
/new/过期清理 → persistFullSession → memories 表（type=full_session，备份）
每日凌晨 1 点 → DailyMemorySummarizer 归纳 → memory_summaries 表
新会话启动 → 优先检索 memory_summaries，其次 memories
```

### 配置

```yaml
dingdong:
  memory:
    enabled: true
    max-results: 5
    extract-threshold: 20
```

---

## 九、定时任务

Agent 可通过内置工具创建 Cron 定时任务，持久化到 SQLite，重启后自动恢复。

**任务类型：**
- `ai_generate`：到时间点调用 Agent 生成动态内容后发送
- `send_message`：到时间点发送固定文本

用户说"每天早上 8 点提醒我喝水" → Agent 自动创建 `0 0 8 * * ?` 定时任务。

---

## 十、LLM Provider

| Provider | 配置项 | 说明 |
|----------|--------|------|
| OpenAI | `dingdong.llm.provider: openai` | 兼容 DeepSeek、通义千问等 |
| Anthropic | `dingdong.llm.provider: anthropic` | Claude Messages API |
| Ollama | `dingdong.llm.provider: ollama` | 本地模型，无需 API Key |

自定义 Provider 只需实现 `LlmProvider` 接口并注册为 Spring Bean。

---

## 十一、错误处理

Agent 内部对常见错误做了友好处理：API 错误、网络超时、图片加载失败、超过最大轮数等均有对应提示信息并记录日志。
