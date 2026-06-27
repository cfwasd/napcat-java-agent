# 配置参考

所有配置项通过 `application.yml` 设置，前缀为 `dingdong`。

---

## 完整配置示例

完整的示例配置见项目根目录 `dingdong-admin/src/main/resources/application.example.yml`，此处列出关键配置块。

### QQ 官方渠道

```yaml
dingdong:
  qq-official:
    enabled: true
    app-id: "YOUR_APP_ID"
    app-secret: ${QQ_OFFICIAL_SECRET:YOUR_SECRET}
    sandbox: true                     # 测试环境用 true，正式发布改为 false
    trigger-mode: all                 # all / wake-word / mention-or-wake
    wake-words:
      - bot
      - 小助手
```

### NapCat (OneBot11) 渠道

```yaml
dingdong:
  qq:
    enabled: true
    adapter:
      type: websocket-client
      websocket-client:
        url: ws://127.0.0.1:3001
        token: ""
    bot:
      self-id: 123456789
      command-prefix: ""
      at-me-trigger: true
      wake-words:
        - 小助手
      super-users:
        - 123456789
```

### LLM 配置

```yaml
dingdong:
  llm:
    provider: openai                  # openai / anthropic / ollama
    openai:
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o
      timeout: 120000
      max-tokens: 4096
      temperature: 0.7
    fallback:
      enabled: true
      provider: openai
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini
```

### Agent 配置

```yaml
dingdong:
  agent:
    enabled: true
    max-react-rounds: 5
    show-tool-process: true
    session-ttl: 7200
    max-history-messages: 40
    enable-vision: true
    skills-path: skills
    text-to-image:
      enabled: true
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}
      model: dall-e-3
    tts:
      enabled: true
      base-url: "https://api.edge-tts.example.com/v1/audio/speech"
      model: "edge-tts"
      default-voice: "zh-CN-XiaoyiNeural"
    builtin:
      web-search:
        enabled: true
        instance: https://your-search-api.example.com
        result-count: 5
```

### Memory 配置

```yaml
dingdong:
  memory:
    enabled: true
    test-data-enabled: false
```

### Scheduler 配置

```yaml
dingdong:
  scheduler:
    enabled: true
```

---

## 配置项详解

### dingdong.qq-official

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `true` | 是否启用 QQ 官方渠道 |
| `app-id` | String | - | QQ 开放平台应用 AppID |
| `app-secret` | String | - | QQ 开放平台应用 AppSecret |
| `sandbox` | boolean | `true` | 沙箱环境（正式发布改为 false） |
| `trigger-mode` | String | `all` | 触发模式：`all` / `wake-word` / `mention-or-wake` |
| `wake-words` | `List<String>` | `[]` | 唤醒词列表 |

### dingdong.qq

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `true` | 是否启用 NapCat 渠道 |
| `adapter.type` | String | `websocket-client` | 通信方式：`websocket-client` / `websocket-server` / `http-client` / `http-server` |
| `bot.self-id` | long | `0` | 机器人 QQ 号 |
| `bot.command-prefix` | String | `""` | 命令前缀 |
| `bot.at-me-trigger` | boolean | `true` | 被 @ 时自动走 Agent |
| `bot.wake-words` | `List<String>` | `[]` | 关键词唤醒列表 |
| `bot.super-users` | `List<long>` | `[]` | 超级管理员 QQ 号列表 |

### dingdong.llm

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `provider` | String | `openai` | LLM 提供商：`openai` / `anthropic` / `ollama` |
| `openai.base-url` | String | - | OpenAI 兼容 API 地址 |
| `openai.api-key` | String | - | API Key |
| `openai.model` | String | - | 模型名称（必须显式配置） |
| `openai.max-tokens` | int | `2000` | 最大生成 Token 数 |
| `openai.temperature` | double | `0.7` | 采样温度 |
| `openai.timeout` | long | `60000` | 请求超时（ms） |
| `anthropic.*` | - | - | 同上结构，Claude 专有配置 |
| `ollama.*` | - | - | 同上结构，Ollama 专有配置 |
| `fallback.enabled` | boolean | `false` | 是否启用备用模型 |
| `fallback.provider` | String | `openai` | 备用模型提供商 |

### dingdong.agent

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `false` | 是否启用 Agent 功能 |
| `max-react-rounds` | int | `5` | ReAct 循环最大轮数 |
| `session-ttl` | long | `3600` | 会话上下文过期时间（秒） |
| `show-tool-process` | boolean | `false` | 是否将工具调用过程发送到聊天 |
| `max-history-messages` | int | `50` | 会话历史最大消息条数 |
| `enable-vision` | boolean | `true` | 是否启用多模态图片理解 |
| `skills-path` | String | `skills` | Agent 技能文件目录 |

### dingdong.core

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `database-path` | String | `"dingdong_data/dingdong.db"` | SQLite 数据库文件路径 |
| `message-post-format` | String | `"array"` | OneBot11 上报格式：`array` 或 `string` |

---

## 多环境配置

Spring Boot 原生支持多环境：

```yaml
# application-dev.yml
dingdong:
  qq:
    adapter:
      type: websocket-client
      websocket-client:
        url: ws://127.0.0.1:3001

# application-prod.yml
dingdong:
  qq:
    adapter:
      type: websocket-client
      websocket-client:
        url: ws://napcat.internal:3001
        token: ${NAPCAT_TOKEN}
  llm:
    openai:
      api-key: ${OPENAI_API_KEY}
```

更多配置项详见 `application.example.yml` 文件。
