package com.napcat.starter.wechat;

import com.napcat.core.adapter.BotAdapter;
import com.napcat.core.api.ApiRequest;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.event.GroupMessageEvent;
import com.napcat.core.event.MessageEvent;
import com.napcat.core.event.PrivateMessageEvent;
import com.napcat.core.event.Sender;
import com.napcat.core.handler.HandlerRegistry;
import com.napcat.core.message.FileSegment;
import com.napcat.core.message.ImageSegment;
import com.napcat.core.message.MessageChain;
import com.napcat.core.message.MessageSegment;
import com.napcat.starter.config.WechatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class AgentWechatPoller implements SmartLifecycle {

    private static final Pattern MEDIA_MARKER = Pattern.compile(
            "(?i)\\[(IMAGE|FILE):(?:url|path|file)=([^\\]]+)]|!\\[[^]]*]\\(([^)]+)\\)");

    @FunctionalInterface
    public interface AgentResponder {
        CompletableFuture<String> chat(long userId, long groupId, String prompt);
    }

    private final AgentWechatClient client;
    private final WechatIdMapper idMapper;
    private final WechatProperties props;
    private final AgentResponder responder;
    private final HandlerRegistry handlerRegistry;
    private final ConcurrentMap<String, Long> lastSeenLocalIds = new ConcurrentHashMap<>();
    private final Set<String> inFlightMessages = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public AgentWechatPoller(AgentWechatClient client, WechatIdMapper idMapper,
                             WechatProperties props, AgentResponder responder) {
        this(client, idMapper, props, responder, null);
    }

    public AgentWechatPoller(AgentWechatClient client, WechatIdMapper idMapper,
                             WechatProperties props, AgentResponder responder,
                             HandlerRegistry handlerRegistry) {
        this.client = client;
        this.idMapper = idMapper;
        this.props = props;
        this.responder = responder;
        this.handlerRegistry = handlerRegistry;
    }

    @Override
    public void start() {
        if (running) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-wechat-poller");
            t.setDaemon(true);
            return t;
        });
        initializeLastSeenMessages();
        executor.scheduleWithFixedDelay(this::safePollOnce, 0,
                Math.max(500, props.getPollIntervalMs()), TimeUnit.MILLISECONDS);
        running = true;
        log.info("AgentWechatPoller started, interval={}ms", props.getPollIntervalMs());
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void initializeLastSeenMessages() {
        try {
            List<AgentWechatChat> chats = client.listChats(props.getChatLimit(), 0);
            for (AgentWechatChat chat : chats) {
                if (chat == null || chat.getId() == null || chat.getId().isBlank()) {
                    continue;
                }
                if (chat.isGroup() && !props.isReplyToGroupMessages()) {
                    continue;
                }
                long maxSeen = client.listMessages(chat.getId(), props.getMessageLimit(), 0)
                        .stream()
                        .filter(m -> m != null && m.getLocalId() > 0)
                        .mapToLong(AgentWechatMessage::getLocalId)
                        .max()
                        .orElse(0L);
                if (maxSeen > 0) {
                    lastSeenLocalIds.put(chat.getId(), maxSeen);
                }
            }
        } catch (Exception e) {
            log.warn("AgentWechatPoller initialize lastSeen failed: {}", e.getMessage());
        }
    }

    public void pollOnce() throws Exception {
        List<AgentWechatChat> chats = client.listChats(props.getChatLimit(), 0);
        for (AgentWechatChat chat : chats) {
            if (chat == null || chat.getId() == null || chat.getId().isBlank()) {
                continue;
            }
            if (chat.isGroup() && !props.isReplyToGroupMessages()) {
                continue;
            }
            pollChat(chat);
        }
    }

    private void pollChat(AgentWechatChat chat) throws Exception {
        List<AgentWechatMessage> messages = client.listMessages(chat.getId(), props.getMessageLimit(), 0)
                .stream()
                .filter(m -> m != null && m.getLocalId() > 0)
                .sorted(Comparator.comparingLong(AgentWechatMessage::getLocalId))
                .toList();

        long lastSeen = lastSeenLocalIds.getOrDefault(chat.getId(), 0L);
        long maxSeen = lastSeen;
        for (AgentWechatMessage message : messages) {
            maxSeen = Math.max(maxSeen, message.getLocalId());
            if (message.getLocalId() <= lastSeen) {
                continue;
            }
            handleNewMessage(chat, message);
        }
        lastSeenLocalIds.put(chat.getId(), maxSeen);
    }

    private static final int WECHAT_MSG_TYPE_IMAGE = 3;

    private void handleNewMessage(AgentWechatChat chat, AgentWechatMessage message) {
        if (props.isIgnoreSelfMessages() && Boolean.TRUE.equals(message.getSelf())) {
            return;
        }
        boolean isImage = message.getType() == WECHAT_MSG_TYPE_IMAGE;
        // 群聊不做图片识别，仅文本/命令/关键词
        if (isImage && chat.isGroup()) {
            return;
        }
        if (!isImage && (message.getContent() == null || message.getContent().isBlank())) {
            return;
        }
        if (!isImage && !shouldTrigger(chat, message)) {
            return;
        }

        String key = chat.getId() + ":" + message.getLocalId();
        if (!inFlightMessages.add(key)) {
            return;
        }

        long userId = idMapper.toUserId(message.getSender() != null ? message.getSender() : chat.getId());
        long groupId = chat.isGroup() ? idMapper.toGroupId(chat.getId()) : 0L;
        String cleanedContent = removeWakeWords(message.getContent());
        if (!isImage && dispatchCommand(chat, message, userId, groupId, cleanedContent)) {
            inFlightMessages.remove(key);
            return;
        }
        String prompt = isImage
                ? buildImagePrompt(chat, message)
                : buildPrompt(chat, message, cleanedContent);
        if (prompt == null || prompt.isBlank()) {
            inFlightMessages.remove(key);
            return;
        }

        responder.chat(userId, groupId, prompt)
                .thenAccept(reply -> sendMarkedText(chat.getId(), reply))
                .exceptionally(ex -> {
                    log.warn("Agent reply failed for wechat message {}", key, ex);
                    return null;
                })
                .whenComplete((ignored, ex) -> inFlightMessages.remove(key));
    }

    private boolean shouldTrigger(AgentWechatChat chat, AgentWechatMessage message) {
        String mode = props.getTriggerMode() == null ? "wake-word" : props.getTriggerMode().trim().toLowerCase();
        String content = message.getContent() == null ? "" : message.getContent();
        return switch (mode) {
            case "all" -> true;
            case "mention-or-wake" -> Boolean.TRUE.equals(message.getMentioned()) || containsWakeWord(content);
            case "private-all-group-mention-or-wake" -> !chat.isGroup()
                    || Boolean.TRUE.equals(message.getMentioned())
                    || containsWakeWord(content);
            case "wake-word" -> containsWakeWord(content);
            default -> containsWakeWord(content);
        };
    }

    private boolean containsWakeWord(String content) {
        if (props.getWakeWords() == null || props.getWakeWords().isEmpty()) {
            return false;
        }
        for (String word : props.getWakeWords()) {
            if (word != null && !word.isBlank() && content.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String buildPrompt(AgentWechatChat chat, AgentWechatMessage message, String content) {
        String senderName = firstNonBlank(message.getSenderName(), message.getSender(), "未知用户");
        String chatName = firstNonBlank(chat.getName(), chat.getRemark(), chat.getId());
        if (chat.isGroup()) {
            return "[微信/群聊:" + chatName + "] [发送者:" + senderName + "] " + content;
        }
        return "[微信/私聊:" + chatName + "] [发送者:" + senderName + "] " + content;
    }

    private String buildImagePrompt(AgentWechatChat chat, AgentWechatMessage message) {
        AgentWechatMediaResult media;
        try {
            media = client.getMedia(chat.getId(), message.getLocalId());
        } catch (Exception e) {
            log.warn("Failed to fetch wechat media for {}:{}", chat.getId(), message.getLocalId(), e);
            return null;
        }
        if (media == null) {
            return null;
        }
        String marker = buildImageMarker(media);
        if (marker == null) {
            return null;
        }
        String senderName = firstNonBlank(message.getSenderName(), message.getSender(), "未知用户");
        String chatName = firstNonBlank(chat.getName(), chat.getRemark(), chat.getId());
        return "[微信/私聊:" + chatName + "] [发送者:" + senderName + "]"
                + " 用户发来一张图片，图片已附在本条消息中。请直接根据图片内容回复，不要调用 fetch_url、web_search 等工具去获取或搜索图片。"
                + " " + marker;
    }

    private String buildImageMarker(AgentWechatMediaResult media) {
        String format = media.getFormat() == null || media.getFormat().isBlank() ? "png" : media.getFormat().toLowerCase();
        if (media.getData() != null && !media.getData().isBlank()) {
            return "[图片:data:image/" + format + ";base64," + media.getData() + "]";
        }
        if (media.getUrl() != null && !media.getUrl().isBlank()) {
            return "[图片:" + media.getUrl() + "]";
        }
        return null;
    }

    private boolean dispatchCommand(AgentWechatChat chat, AgentWechatMessage message,
                                    long userId, long groupId, String content) {
        if (handlerRegistry == null || content == null || content.isBlank()) {
            return false;
        }
        MessageEvent event = chat.isGroup()
                ? buildGroupEvent(chat, message, userId, groupId, content)
                : buildPrivateEvent(message, userId, content);
        return !handlerRegistry.dispatch(event).isEmpty();
    }

    private GroupMessageEvent buildGroupEvent(AgentWechatChat chat, AgentWechatMessage message,
                                              long userId, long groupId, String content) {
        GroupMessageEvent event = new GroupMessageEvent();
        fillMessageEvent(event, message, userId, content);
        event.setGroupId(groupId);
        event.setSubType("normal");
        event.setMessageSeq(message.getLocalId());
        event.setApi(wechatApi(chat.getId()));
        return event;
    }

    private PrivateMessageEvent buildPrivateEvent(AgentWechatMessage message, long userId, String content) {
        PrivateMessageEvent event = new PrivateMessageEvent();
        fillMessageEvent(event, message, userId, content);
        event.setSubType("friend");
        event.setApi(wechatApi(message.getChatId()));
        return event;
    }

    private void fillMessageEvent(MessageEvent event, AgentWechatMessage message, long userId, String content) {
        event.setTime(System.currentTimeMillis() / 1000);
        event.setPostType("message");
        event.setSelfId(0L);
        event.setMessageId((int) Math.min(Integer.MAX_VALUE, message.getLocalId()));
        event.setUserId(userId);
        event.setRawMessage(content);
        event.setMessage(MessageChain.ofText(content));
        Sender sender = new Sender();
        sender.setUserId(userId);
        sender.setNickname(firstNonBlank(message.getSenderName(), message.getSender(), "微信用户"));
        event.setSender(sender);
    }

    private NapCatApi wechatApi(String chatId) {
        return new NapCatApi(new WechatReplyAdapter(chatId));
    }

    private class WechatReplyAdapter implements BotAdapter {
        private final String chatId;

        private WechatReplyAdapter(String chatId) {
            this.chatId = chatId;
        }

        @Override
        public String getId() {
            return "agent-wechat-reply";
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendApiRequest(ApiRequest<?> request) {
            Object message = request.getParams() instanceof Map<?, ?> params ? params.get("message") : null;
            sendWechatMessage(chatId, message);
        }

        @Override
        public void setMessageHandler(Consumer<String> handler) {
        }
    }

    private void sendWechatMessage(String chatId, Object message) {
        if (message instanceof MessageChain chain) {
            sendMessageChain(chatId, chain);
            return;
        }
        sendMarkedText(chatId, message == null ? null : String.valueOf(message));
    }

    private void sendMessageChain(String chatId, MessageChain chain) {
        StringBuilder textBuffer = new StringBuilder();
        for (MessageSegment segment : chain) {
            if (segment instanceof ImageSegment image) {
                flushText(chatId, textBuffer);
                sendImage(chatId, firstNonBlank(image.getUrl(), image.getFile()));
            } else if (segment instanceof FileSegment file) {
                flushText(chatId, textBuffer);
                sendFile(chatId, file.getFile());
            } else {
                textBuffer.append(MessageChain.of(segment).toPlainText());
            }
        }
        flushText(chatId, textBuffer);
    }

    private void sendMarkedText(String chatId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = MEDIA_MARKER.matcher(text);
        int lastEnd = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            sendPlainText(chatId, text.substring(lastEnd, matcher.start()).trim());
            String markdownImage = matcher.group(3);
            if (markdownImage != null) {
                sendImage(chatId, markdownImage.trim());
            } else {
                String type = matcher.group(1);
                String value = matcher.group(2) == null ? "" : matcher.group(2).trim();
                if ("IMAGE".equalsIgnoreCase(type)) {
                    sendImage(chatId, value);
                } else if ("FILE".equalsIgnoreCase(type)) {
                    sendFile(chatId, value);
                }
            }
            lastEnd = matcher.end();
        }
        if (!matched) {
            sendPlainText(chatId, text.strip());
            return;
        }
        sendPlainText(chatId, text.substring(lastEnd).trim());
    }

    private void flushText(String chatId, StringBuilder textBuffer) {
        if (!textBuffer.isEmpty()) {
            sendMarkedText(chatId, textBuffer.toString());
            textBuffer.setLength(0);
        }
    }

    private void sendPlainText(String chatId, String text) {
        if (text != null && !text.isBlank()) {
            sendReply(chatId, text.strip());
        }
    }

    private void sendImage(String chatId, String image) {
        if (image == null || image.isBlank()) {
            return;
        }
        try {
            AgentWechatSendResult result = client.sendImage(chatId, image.strip());
            if (result == null || !result.isSuccess()) {
                log.warn("agent-wechat image send failed for chat {}: {}", chatId,
                        result == null ? "empty response" : result.getError());
            }
        } catch (Exception e) {
            log.warn("Failed to send wechat image to {}", chatId, e);
        }
    }

    private void sendFile(String chatId, String file) {
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            AgentWechatSendResult result = client.sendFile(chatId, file.strip());
            if (result == null || !result.isSuccess()) {
                log.warn("agent-wechat file send failed for chat {}: {}", chatId,
                        result == null ? "empty response" : result.getError());
            }
        } catch (Exception e) {
            log.warn("Failed to send wechat file to {}", chatId, e);
        }
    }

    private String extractText(Object message) {
        if (message instanceof MessageChain chain) {
            return chain.toPlainText();
        }
        return message == null ? null : String.valueOf(message);
    }

    private String removeWakeWords(String content) {
        if (content == null || props.getWakeWords() == null || props.getWakeWords().isEmpty()) {
            return content;
        }
        String result = content;
        for (String word : props.getWakeWords()) {
            if (word != null && !word.isBlank()) {
                result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(word), "");
            }
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    private void sendReply(String chatId, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        try {
            AgentWechatSendResult result = client.sendText(chatId, reply.strip());
            if (result == null || !result.isSuccess()) {
                log.warn("agent-wechat send failed for chat {}: {}", chatId,
                        result == null ? "empty response" : result.getError());
            }
        } catch (Exception e) {
            log.warn("Failed to send wechat reply to {}", chatId, e);
        }
    }

    private void safePollOnce() {
        try {
            pollOnce();
        } catch (Exception e) {
            log.warn("AgentWechatPoller poll failed: {}", e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
