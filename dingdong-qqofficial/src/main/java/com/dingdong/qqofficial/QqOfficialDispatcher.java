package com.dingdong.qqofficial;

import com.dingdong.channel.api.*;
import com.dingdong.qqofficial.event.QqOfficialGroupMessageEvent;
import com.dingdong.qqofficial.event.QqOfficialPrivateMessageEvent;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 官方事件分发器。
 */
@Slf4j
public class QqOfficialDispatcher implements Consumer<JsonNode> {

    private static final Set<String> MESSAGE_EVENTS = Set.of(
            "C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE");
    private static final Pattern MEDIA_MARKER = Pattern.compile(
            "(?i)\\[(IMAGE|FILE):(?:url|path|file)=([^\\]]+)]");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[[^\\]]*\\]\\(([^)]+)\\)");

    @FunctionalInterface
    public interface AgentInvoker {
        CompletableFuture<String> chat(long userId, long groupId, String prompt);
    }

    private final QqOfficialChannel channel;
    private final QqOfficialIdMapper idMapper;
    private final QqOfficialProperties props;
    private final Consumer<ChannelEvent> eventConsumer;
    private final AgentInvoker agentInvoker;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public QqOfficialDispatcher(QqOfficialChannel channel, Consumer<ChannelEvent> eventConsumer,
                                AgentInvoker agentInvoker) {
        this.channel = channel;
        this.idMapper = channel.getIdMapper();
        this.props = channel.getProperties();
        this.eventConsumer = eventConsumer;
        this.agentInvoker = agentInvoker;
    }

    @Override
    public void accept(JsonNode event) {
        String eventType = event.path("__event_type").asText("");
        if (eventType.isEmpty()) return;
        if (!MESSAGE_EVENTS.contains(eventType)) return;

        String msgId = event.path("id").asText("");
        String msgSeq = event.path("msg_seq").asText(msgId);
        if (msgId.isBlank()) return;
        if (!inFlight.add(msgSeq)) return;

        try {
            dispatchMessage(eventType, event);
        } finally {
            inFlight.remove(msgSeq);
        }
    }

    private void dispatchMessage(String eventType, JsonNode event) {
        boolean isC2c = "C2C_MESSAGE_CREATE".equals(eventType);
        boolean isGroupAt = "GROUP_AT_MESSAGE_CREATE".equals(eventType);
        boolean isGroupFull = "GROUP_MESSAGE_CREATE".equals(eventType);

        JsonNode author = event.path("author");
        String userOpenidRaw = author.path("user_openid").asText("");
        if (userOpenidRaw.isBlank()) userOpenidRaw = author.path("member_openid").asText("");
        if (userOpenidRaw.isBlank()) return;
        final String userOpenid = userOpenidRaw;

        String content = event.path("content").asText("");
        String groupOpenid = (isGroupAt || isGroupFull) ? event.path("group_openid").asText("") : "";
        String cleanedContent = removeAtMentions(content);
        String cleanedContentFinal = removeWakeWords(cleanedContent);
        long userId = idMapper.toUserId(userOpenid);
        long groupId = (isGroupAt || isGroupFull) ? idMapper.toGroupId(groupOpenid) : 0L;

        ChannelMessageEvent msgEvent = buildChannelEvent(event, eventType, userOpenid, groupOpenid,
                content, userId, groupId);

        if (eventConsumer != null) eventConsumer.accept(msgEvent);

        if (!shouldTrigger(isC2c, isGroupAt, isGroupFull, content, event)) return;

        String prompt = buildPrompt(event, eventType, cleanedContentFinal);
        if (prompt == null || prompt.isBlank()) return;

        agentInvoker.chat(userId, groupId, prompt)
                .thenAccept(reply -> {
                    if (reply != null && !reply.isBlank()) {
                        sendReply(eventType, userOpenid, groupOpenid, reply, event.path("id").asText(""));
                    }
                })
                .exceptionally(ex -> {
                    log.warn("Agent reply failed for msg {}", event.path("id").asText(""), ex);
                    return null;
                });
    }

    private ChannelMessageEvent buildChannelEvent(JsonNode event, String eventType,
                                                   String userOpenid, String groupOpenid,
                                                   String content, long userId, long groupId) {
        ChannelMessageEvent msgEvent;
        boolean isGroup = groupOpenid != null && !groupOpenid.isBlank();

        if (isGroup) {
            QqOfficialGroupMessageEvent ge = new QqOfficialGroupMessageEvent();
            ge.setGroupOpenid(groupOpenid);
            ge.setUserOpenid(userOpenid);
            ge.setAtBot("GROUP_AT_MESSAGE_CREATE".equals(eventType));
            msgEvent = ge;
        } else {
            QqOfficialPrivateMessageEvent pe = new QqOfficialPrivateMessageEvent();
            pe.setUserOpenid(userOpenid);
            msgEvent = pe;
        }

        msgEvent.setChannelId("qqofficial");
        msgEvent.setTimestamp(System.currentTimeMillis() / 1000);
        msgEvent.setPostType("message");
        msgEvent.setMessageId(Math.abs(event.path("id").asText("").hashCode()));
        msgEvent.setPlainText(content);

        ChannelMessageTarget target = new ChannelMessageTarget();
        target.setUser(ChannelIdentity.of("qqofficial", userOpenid, userId));
        if (isGroup) target.setGroup(ChannelIdentity.of("qqofficial", groupOpenid, groupId));
        target.setTargetType(isGroup ? "group" : "private");
        msgEvent.setMessageTarget(target);

        ChannelMessageSender sender = new ChannelMessageSender();
        sender.setUserId(ChannelIdentity.of("qqofficial", userOpenid, userId));
        sender.setNickname(userOpenid);
        msgEvent.setSender(sender);

        msgEvent.setApi(new QqOfficialReplySender(channel, userOpenid, groupOpenid,
                event.path("id").asText(""), isGroup));

        return msgEvent;
    }

    private boolean shouldTrigger(boolean isC2c, boolean isGroupAt, boolean isGroupFull,
                                  String content, JsonNode event) {
        String mode = props.getTriggerMode();
        if (mode == null || mode.isBlank()) mode = "private-all-group-mention-or-wake";
        boolean hasWakeWord = containsWakeWord(content);
        boolean mentioned = isGroupAt || isMentioned(event);
        return switch (mode) {
            case "all" -> true;
            case "wake-word" -> hasWakeWord;
            case "mention-or-wake" -> hasWakeWord || mentioned;
            default -> isC2c || hasWakeWord || mentioned;
        };
    }

    private boolean containsWakeWord(String content) {
        if (props.getWakeWords() == null || props.getWakeWords().isEmpty()) return false;
        for (String word : props.getWakeWords()) {
            if (word != null && !word.isBlank() && content.contains(word)) return true;
        }
        return false;
    }

    private boolean isMentioned(JsonNode event) {
        JsonNode mentions = event.path("mentions");
        if (mentions.isArray()) {
            for (JsonNode m : mentions) {
                if (m.path("is_you").asBoolean(false)) return true;
            }
        }
        return false;
    }

    private String removeWakeWords(String content) {
        if (content == null || props.getWakeWords() == null || props.getWakeWords().isEmpty()) return content;
        String result = content;
        for (String word : props.getWakeWords()) {
            if (word != null && !word.isBlank()) {
                result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(word), "");
            }
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    private String removeAtMentions(String content) {
        if (content == null || content.isBlank()) return content;
        return content.replaceAll("<@[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private String buildPrompt(JsonNode event, String eventType, String content) {
        JsonNode author = event.path("author");
        String rawName = author.path("user_openid").asText("");
        String senderName = rawName.isBlank() ? author.path("member_openid").asText("QQ用户") : rawName;

        if ("GROUP_AT_MESSAGE_CREATE".equals(eventType) || "GROUP_MESSAGE_CREATE".equals(eventType)) {
            String groupName = event.path("group_openid").asText("群聊");
            return "[QQ官方/群聊:" + groupName + "] [发送者:" + senderName + "] " + content;
        }
        return "[QQ官方/私聊] [发送者:" + senderName + "] " + content;
    }

    private void sendReply(String eventType, String userOpenid, String groupOpenid,
                           String reply, String msgId) {
        if (reply == null || reply.isBlank()) return;
        boolean isGroup = groupOpenid != null && !groupOpenid.isBlank();
        String target = isGroup ? groupOpenid : userOpenid;

        try {
            String normalized = normalizeMarkdownImages(reply);
            String text = convertMediaMarkers(normalized, target, isGroup, msgId);
            if (!text.isBlank()) {
                if (isGroup) channel.getApi().sendGroupMessage(groupOpenid, text, msgId);
                else channel.getApi().sendC2cMessage(userOpenid, text, msgId);
            }
        } catch (Exception e) {
            log.warn("Failed to send qq-official reply", e);
        }
    }

    private String normalizeMarkdownImages(String text) {
        if (text == null || text.isBlank()) return text;
        Matcher matcher = MARKDOWN_IMAGE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            matcher.appendReplacement(sb, "[IMAGE:url=" + Matcher.quoteReplacement(url) + "]");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String convertMediaMarkers(String text, String target, boolean isGroup, String msgId) {
        Matcher matcher = MEDIA_MARKER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String type = matcher.group(1);
            String value = matcher.group(2);
            matcher.appendReplacement(result, "");
            if (value != null && !value.isBlank()) {
                sendMediaAsync(target, isGroup, type, value.trim(), msgId);
            }
        }
        matcher.appendTail(result);
        return result.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private void sendMediaAsync(String target, boolean isGroup, String type, String value, String msgId) {
        if (value == null || value.isBlank()) return;
        try {
            int fileType = "IMAGE".equalsIgnoreCase(type) ? 1 : 4;
            String fileInfo;
            if (isGroup) {
                fileInfo = channel.getApi().uploadGroupFile(target, fileType, value);
                channel.getApi().sendGroupMedia(target, fileInfo, msgId);
            } else {
                fileInfo = channel.getApi().uploadC2cFile(target, fileType, value);
                channel.getApi().sendC2cMedia(target, fileInfo, msgId);
            }
        } catch (Exception e) {
            log.warn("Failed to send {} via qq-official", type, e);
        }
    }
}
