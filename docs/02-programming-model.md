# 编程模型

框架同时支持**注解驱动**和**接口驱动**两种编程模型，两者最终汇入同一路由表，行为完全一致。所有 Bot 方法在 QQ 官方渠道和 NapCat 渠道均可复用。

---

## 一、注解驱动

所有注解位于包 `com.dingdong.core.annotation` 下。

### 1.1 事件监听注解

在方法上标注，表示监听对应类型的事件。

| 注解 | 适用参数类型 | 说明 |
|------|------------|------|
| `@OnGroupMessage` | `GroupMessageEvent` / `ChannelEvent` | 群聊消息事件 |
| `@OnPrivateMessage` | `PrivateMessageEvent` / `ChannelEvent` | 私聊消息事件 |

> **注意**：`@OnNotice`、`@OnRequest`、`@OnMetaEvent` 注解已定义，但当前注解驱动模型暂未实现对应的事件分发逻辑。通知、请求、元事件请通过接口驱动模型的 `EventHandler` 处理。

**示例：**

```java
@Component
public class HelloBot {

    @OnGroupMessage
    public void onGroup(GroupMessageEvent event) {
        if (event.getRawMessage().contains("hello")) {
            event.reply("Hello!");
        }
    }

    @OnPrivateMessage
    public void onPrivate(PrivateMessageEvent event) {
        event.reply("私聊收到：" + event.getPlainText());
    }
}
```

**多事件类型叠加：**

同一个方法可以加多个事件注解，满足**任一**即触发（OR 关系）。

```java
@OnGroupMessage
@OnPrivateMessage
public void onAny(MessageEvent event) {
    // 群聊或私聊都会进入这里
}
```

---

### 1.2 命令注解

`@Command` 用于匹配固定格式的指令，**必须与事件注解叠加使用**。

```java
public @interface Command {
    String value();
    int priority() default 1;           // 优先级，数值越小越先执行
    String description() default "";    // 命令描述，用于 /help 展示
    boolean adminOnly() default false;  // true 时普通成员在 /help 中看不到
}
```

**规则：**

- 叠加在事件注解上时，表示**同时满足**（AND 关系）
- 消息必须匹配命令模板，参数用 `{}` 包裹
- 不匹配的参数化命令会继续向下路由
- 实际匹配时会在模板前拼接 `dingdong.qq.bot.command-prefix`（默认为空字符串）

**示例：**

```java
@Component
public class CommandBot {

    // 匹配："/天气 北京"
    @OnGroupMessage
    @Command("/天气 {city}")
    public void weather(GroupMessageEvent event, @Param("city") String city) {
        event.reply("查询 " + city + " 的天气");
    }

    // 匹配："/帮助"
    @OnGroupMessage
    @Command("/帮助")
    public void help(GroupMessageEvent event) {
        event.reply("可用指令：/天气 /帮助");
    }
}
```

**命令参数提取：**

```java
@OnGroupMessage
@Command("/禁言 {user} {minutes}")
public void ban(GroupMessageEvent event,
                @Param("user") long userId,
                @Param("minutes") int minutes) {
    // userId 会被自动从 @user 的 QQ 号提取
    // minutes 会被自动转为 int
}
```

---

### 1.3 过滤注解

与事件注解叠加，增加额外过滤条件。

| 注解 | 说明 | 适用场景 |
|------|------|---------|
| `@MentionFilter` | 要求消息中 @ 了当前机器人 | Agent 自动回复 |
| `@RoleFilter(Role.ADMIN)` | 要求发送者是指定角色 | 管理员命令 |
| `@WakeFilter` | 要求消息包含配置的唤醒词 | 关键词唤醒 |

**示例：**

```java
// 只有被 @ 时才触发
@OnGroupMessage
@MentionFilter
public void onAtMe(GroupMessageEvent event) {
    event.reply("你叫我？");
}

// 管理员或超级用户才能执行
@OnGroupMessage
@OnPrivateMessage
@Command("/清理")
@RoleFilter(RoleFilter.Role.SUPERUSER)
public void clear(MessageEvent event) { }

// 消息包含唤醒词时触发
@OnGroupMessage
@WakeFilter
public void onWake(GroupMessageEvent event) {
    event.reply("我在！");
}
```

---

### 1.4 渠道兼容写法

推荐同时标注 `@OnGroupMessage` 和 `@OnPrivateMessage`，并使用 `ChannelEvent` 作为参数类型，使方法同时在 QQ 官方和 NapCat 渠道生效：

```java
@OnGroupMessage
@OnPrivateMessage
@Command("/签到")
public String checkin(ChannelEvent event) {
    long userId = resolveUserId(event);
    // 渠道无关的业务逻辑
    return checkInService.doCheckin(userId);
}

// QQ 官方渠道可以额外返回 Markdown + 按钮面板
@OnGroupMessage
@OnPrivateMessage
@Command("/修仙面板")
public String cultivationPanel(ChannelEvent event) {
    if ("qqofficial".equals(event.getChannelId())) {
        // 发送 Markdown + 按钮面板
        return sendWithKeyboard(event, markdown, keyboardJson);
    }
    // 其他渠道返回纯文本
    return buildPlainText();
}
```

### 1.5 Agent 相关注解

| 注解 | 说明 |
|------|------|
| `@Tool` | 标记一个方法为 Agent 可调用的工具 |
| `@ToolParam` | 标记工具参数的描述和约束 |

**@Tool 示例：**

```java
@Component
public class Tools {

    @Tool(name = "get_weather", description = "查询指定城市的天气")
    public String getWeather(
        @ToolParam(description = "城市名称，如北京", required = true) String city
    ) {
        return "北京 晴 25°C";
    }

    @Tool(name = "calculate", description = "数学计算")
    public double calculate(
        @ToolParam(description = "表达式，如 1+2") String expression
    ) {
        return 3.0;
    }
}
```

### 1.6 组合注解（Meta-Annotation）

框架支持自定义组合注解。定义时将框架注解作为元注解即可：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@OnGroupMessage
@MentionFilter
public @interface OnGroupAtMe { }

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Command
@RoleFilter(RoleFilter.Role.ADMIN)
public @interface AdminCommand {
    String value();
}
```

---

## 二、接口驱动

所有接口位于包 `com.dingdong.core.handler` 下。

### 2.1 通用事件处理器

```java
public interface EventHandler<E extends OB11Event> {
    Class<E> getEventType();
    void handle(E event);

    interface GroupMessageHandler extends EventHandler<GroupMessageEvent> { }
    interface PrivateMessageHandler extends EventHandler<PrivateMessageEvent> { }
}
```

**示例：**

```java
@Component
public class WelcomeHandler implements EventHandler.GroupMessageHandler {
    @Override
    public void handle(GroupMessageEvent event) {
        if (event.getMessage().toPlainText().contains("新人")) {
            event.reply("欢迎新人！");
        }
    }
}
```

### 2.2 命令处理器

```java
public interface CommandHandler {
    String getCommand();
    void handle(MessageEvent event, CommandArgs args);

    interface FilterableCommandHandler extends CommandHandler {
        default boolean filter(MessageEvent event) { return true; }
    }
}
```

### 2.3 手动注册器

```java
public interface BotInitializer {
    void initialize(BotDispatcher dispatcher);
}
```

---

## 三、返回值处理

注解驱动的方法可以有返回值，框架会自动处理：

| 返回值类型 | 行为 |
|-----------|------|
| `void` | 无操作 |
| `String` | 自动回复文本 |
| `MessageChain` | 自动回复消息链 |
| `null` | 不回复（用于已手动回复的场景） |

**示例：**

```java
@OnGroupMessage
@Command("/时间")
public String time() {
    return new Date().toString();
}

@OnGroupMessage
@Command("/图片")
public MessageChain image() {
    return MessageChain.ofText("给你一张图：").image("https://example.com/a.jpg");
}
```

---

## 四、异常处理

框架提供全局异常处理器。方法内抛出 `StopRoutingException` 可阻止后续处理器执行：

```java
@OnGroupMessage
public void filterSpam(GroupMessageEvent event) {
    if (isSpam(event)) {
        throw new StopRoutingException();
    }
}
```

---

## 五、执行顺序与优先级

### 5.1 路由优先级

框架按**优先级数值从小到大**依次匹配并执行。每个方法可能叠加多个注解，最终优先级取**所有注解 priority 的最小值**：

| 优先级数值 | 注解组合 | 说明 |
|-----------|---------|------|
| 1 | `@Command` | 默认 1，可通过 `priority()` 自定义 |
| 10 | `@MentionFilter` | 默认 10，可通过 `priority()` 自定义 |
| 10 | `@WakeFilter` | 默认 10，可通过 `priority()` 自定义 |
| 10 | `@RoleFilter` | 默认 10，可通过 `priority()` 自定义 |
| 100 | `@OnGroupMessage` / `@OnPrivateMessage` | 默认 100，可通过 `priority()` 自定义 |

### 5.2 阻止后续路由

默认情况下，匹配的处理器会**顺序执行**。抛出 `StopRoutingException` 才会阻止后续处理器执行。命令匹配成功后，框架默认**直接返回**，不再执行后续 handler。
