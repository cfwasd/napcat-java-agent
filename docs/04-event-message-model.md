# 事件与消息模型

本文档描述框架对事件和消息的封装，涵盖 NapCat OneBot11 协议和 QQ 官方 API v2 两种渠道。

---

## 一、渠道抽象

框架通过 **Channel API** 统一抽象不同渠道：

```java
// 所有渠道事件的基类
public interface ChannelEvent {
    String getChannelId();          // "qqofficial" / "napcat"
    long getUserId();
    long getGroupId();
    String getPlainText();
}

// 消息事件（含回复能力）
public interface ChannelMessageEvent extends ChannelEvent {
    ChannelMessageTarget getMessageTarget();
    Object getApi();                 // 渠道专用 API
    String getRawContent();         // 原始消息内容（@ 信息等）
    List<Long> getMentions();       // 被 @ 的用户列表
}
```

Bot 方法统一使用 `ChannelEvent` 参数，即可在双渠道复用：

```java
@OnGroupMessage
@OnPrivateMessage
@Command("/签到")
public String checkin(ChannelEvent event) {
    // 无需关心是哪个渠道
    return checkInService.doCheckin(event.getUserId());
}
```

---

## 二、NapCat (OneBot11) 事件体系

所有事件继承 `OB11Event`，位于 `com.dingdong.core.event`。

```
OB11Event
├── MessageEvent
│   ├── GroupMessageEvent
│   └── PrivateMessageEvent
├── NoticeEvent
│   ├── GroupIncreaseEvent
│   ├── GroupDecreaseEvent
│   ├── GroupAdminEvent
│   ├── GroupBanEvent
│   ├── FriendAddEvent
│   ├── GroupRecallEvent
│   ├── FriendRecallEvent
│   ├── GroupUploadEvent
│   ├── NotifyEvent
│   ├── LuckyKingEvent
│   ├── HonorEvent
│   └── GroupTitleEvent
├── RequestEvent
│   ├── FriendRequestEvent
│   └── GroupRequestEvent
└── MetaEvent
    ├── LifecycleEvent
    └── HeartbeatEvent
```

### MessageEvent

```java
public abstract class MessageEvent extends OB11Event {
    private int messageId;
    private long userId;
    private MessageChain message;
    private String rawMessage;
    private Sender sender;

    public void reply(String text);
    public void reply(MessageChain chain);
    public String getPlainText();
}
```

### GroupMessageEvent

```java
public class GroupMessageEvent extends MessageEvent {
    private long groupId;
    private String subType;
    private long messageSeq;
    private Anonymous anonymous;
}
```

---

## 三、消息链（MessageChain）

OneBot11 的消息是**段（Segment）数组**，框架封装为 `MessageChain`。支持 array / string（CQ 码）双格式反序列化。

### 构造消息链

```java
MessageChain chain = MessageChain.ofText("你好")
    .at(123456789L)
    .text("看看这个")
    .image("https://example.com/pic.jpg")
    .reply(event.getMessageId());

event.reply(chain);
```

### 静态工厂方法

```java
MessageChain.of(MessageSegment segment)
MessageChain.ofText(String text)
MessageChain.ofAt(long qq)
MessageChain.ofImage(String file)
MessageChain.ofRecord(String file)
MessageChain.ofReply(int messageId)
MessageChain.ofMarkdown(String content)
MessageChain.ofJson(String data)
MessageChain.ofForward(List<NodeSegment> nodes)
```

### 消息段类型

| OneBot11 类型 | 说明 |
|--------------|------|
| `text` | 纯文本 |
| `at` | @某人 / @全体成员 |
| `face` | QQ 表情 ID |
| `image` | 图片 URL/路径/Base64 |
| `record` | 语音 |
| `video` | 视频 |
| `file` | 文件 |
| `reply` | 回复某条消息 |
| `markdown` | Markdown 消息 |
| `json` | JSON 卡片 |
| `node` | 合并转发节点 |
| `forward` | 合并转发 |

### 从事件解析

```java
@OnGroupMessage
public void onGroup(GroupMessageEvent event) {
    MessageChain msg = event.getMessage();

    for (MessageSegment segment : msg) {
        if (segment instanceof TextSegment text) { }
        else if (segment instanceof ImageSegment img) { }
        else if (segment instanceof AtSegment at) { }
    }

    String plain = msg.toPlainText();
    List<String> images = msg.getImages();
    List<Long> ats = msg.getAts();
    String agentPrompt = msg.toAgentPrompt();  // 带 [图片:url] 标记
}
```

---

## 四、API 客户端

### NapCat API

```java
@Autowired
private NapCatApi api;

api.sendGroupMessage(123456789L, "Hello");
api.sendPrivateMessage(111111111L, "Hello");
api.deleteMessage(12345);
api.getLoginInfo();
```

完整 API 列表涵盖 OneBot11 所有标准接口及 NapCat 扩展接口（精华消息、群公告、文件管理、OCR、翻译等）。

### QQ 官方 API

QQ 官方渠道通过 `ChannelMessageEvent.getApi()` 获取渠道专用 API，支持 Markdown + 键盘按钮等能力：

```java
// 发送 Markdown 消息
api.reply(markdownContent);

// 发送 Markdown + 按钮面板
api.replyWithKeyboard(markdownContent, keyboardJson);
```

---

## 五、Sender 信息

```java
public class Sender {
    private long userId;
    private String nickname;
    private String sex;
    private int age;
    private String card;         // 群名片
    private String role;         // owner / admin / member
    private String title;        // 专属头衔

    public boolean isAdmin();
    public boolean isOwner();
}
```

---

## 六、事件上下文（EventContext）

```java
EventContext ctx = EventContextHolder.get();
ctx.getAttrs().put("key", value);
```

上下文在同一线程内有效。跨线程（如 Agent 异步回调）时，`MessageEvent` 自身持有 `api` 引用。
