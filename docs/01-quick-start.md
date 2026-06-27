# 快速开始

基于 NapCat OneBot11 协议 + QQ 官方 API v2 的 Java Bot 开发框架，集成 AI Agent 能力。

---

## 前置要求

- JDK 17+
- Maven 3.8+
- （NapCat 渠道）已部署并配置好的 NapCat（[安装指南](https://napneko.github.io/guide/start-install)）
- （QQ 官方渠道）在 [QQ 开放平台](https://q.qq.com/) 创建应用，获取 AppID 和 AppSecret

## 快速开始

> **注意**：目前尚未发布到 Maven Central，请按以下步骤本地安装后开发。

### 1. 克隆并安装到本地

```bash
git clone https://github.com/cfwasd/dingdong-bot.git
cd dingdong-bot
mvn install -DskipTests
```

### 2. 配置

复制示例配置并编辑：

```bash
cp dingdong-admin/src/main/resources/application.example.yml dingdong-admin/src/main/resources/application.yml
```

根据你的渠道修改配置：

**NapCat 渠道：**

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
```

**QQ 官方渠道：**

```yaml
dingdong:
  qq-official:
    enabled: true
    app-id: "YOUR_APP_ID"
    app-secret: ${QQ_OFFICIAL_SECRET:YOUR_SECRET}
    sandbox: true
```

两个渠道可以同时启用。

### 3. 在 dingdong-admin 中编写 Bot

直接在 `dingdong-admin` 模块下创建你的 Bot 类即可：

```java
@Component
public class HelloBot {

    @OnGroupMessage
    @Command("/hello")
    public void hello(GroupMessageEvent event) {
        event.reply("Hello 叮咚!");
    }

    @OnGroupMessage
    public void onGroup(GroupMessageEvent event) {
        if (event.getRawMessage().contains("在吗")) {
            event.reply("在的！");
        }
    }
}
```

然后运行 `dingdong-admin` 的 `DinDongApplication` main 方法启动 Bot。

更多示例见 `dingdong-admin/src/main/java/com/dingdong/admin/bot/` 目录。

---

### 在自己的项目中使用

如果你想在自己的项目中引入，先执行上面的 `mvn install`，然后在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>dingdong-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

如需 Agent 能力，再添加一个 LLM Provider：

```xml
<!-- OpenAI 协议兼容（含 DeepSeek、通义千问等，支持多模态/vision） -->
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>dingdong-llm-openai</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- 或 Anthropic Claude -->
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>dingdong-llm-anthropic</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- 或 Ollama 本地模型 -->
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>dingdong-llm-ollama</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 进阶示例

### 接口式 Handler

```java
@Component
public class WeatherCommand implements CommandHandler {

    @Override
    public String getCommand() {
        return "/接口天气 {city}";
    }

    @Override
    public void handle(MessageEvent event, CommandArgs args) {
        String city = args.get("city");
        event.reply("【接口方式】" + city + " 天气晴朗");
    }
}
```

### 启用 AI Agent

```yaml
dingdong:
  agent:
    enabled: true
    max-react-rounds: 5
  llm:
    provider: openai
    openai:
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini
  memory:
    enabled: false
  scheduler:
    enabled: true
```

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

配置 `dingdong.qq.bot.at-me-trigger: true` 后，被 @ 或包含唤醒词时会自动走 Agent 流程，无需额外写 Handler。

---

## 微信接入

通过 [agent-wechat](https://github.com/thisnick/agent-wechat) 容器接入微信。详见项目根目录 [README.md](../README.md#微信接入) 或在线文档站。

---

## 模块架构

```
dingdong-bot/
├── dingdong-parent                  # BOM，统一依赖版本
├── dingdong-core                    # OneBot11 协议、通信适配器、事件路由
├── dingdong-agent                   # LLM Agent 引擎、Tool 注册、ReAct 循环
├── dingdong-cultivation             # 修仙系统模块
├── dingdong-channel-api             # 渠道抽象 API
├── dingdong-qqofficial              # QQ 官方 API v2 渠道实现
├── dingdong-llm-providers           # LLM 厂商实现
│   ├── dingdong-llm-openai          # OpenAI 协议兼容（含多模态/vision）
│   ├── dingdong-llm-anthropic       # Claude
│   └── dingdong-llm-ollama          # Ollama 本地模型
├── dingdong-boot-starter            # Spring Boot 自动配置
└── dingdong-admin                   # 示例机器人应用
```
