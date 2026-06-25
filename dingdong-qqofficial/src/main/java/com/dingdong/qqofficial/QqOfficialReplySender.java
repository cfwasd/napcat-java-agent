package com.dingdong.qqofficial;

import com.dingdong.channel.api.ChannelIdentity;
import com.dingdong.channel.api.ChannelMessageTarget;
import com.dingdong.channel.api.MessageResult;
import com.dingdong.channel.api.MessageSender;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * QQ 官方通道的带上下文的 MessageSender。
 * 用于在事件处理中回复当前消息。
 */
@Slf4j
public class QqOfficialReplySender implements MessageSender {

    private final QqOfficialChannel channel;
    private final String userOpenid;
    private final String groupOpenid;
    private final String msgId;
    private final boolean isGroup;

    public QqOfficialReplySender(QqOfficialChannel channel, String userOpenid,
                                  String groupOpenid, String msgId, boolean isGroup) {
        this.channel = channel;
        this.userOpenid = userOpenid;
        this.groupOpenid = groupOpenid;
        this.msgId = msgId;
        this.isGroup = isGroup;
    }

    @Override
    public CompletableFuture<MessageResult> reply(String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String target = isGroup ? groupOpenid : userOpenid;
                String normalized = normalizeMarkdownImages(text);
                String plain = convertMediaMarkers(normalized, target, isGroup, msgId);
                if (!plain.isBlank()) {
                    if (isGroup) channel.getApi().sendGroupMessage(groupOpenid, plain, msgId);
                    else channel.getApi().sendC2cMessage(userOpenid, plain, msgId);
                }
                return MessageResult.ok(msgId);
            } catch (IOException e) {
                log.warn("QqOfficialReplySender reply failed", e);
                return MessageResult.fail(e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<MessageResult> sendTo(ChannelMessageTarget target, String text) {
        if (target.isGroup()) return sendGroupMessage(target.getGroup(), text);
        return sendPrivateMessage(target.getUser(), text);
    }

    @Override
    public CompletableFuture<MessageResult> sendPrivateMessage(ChannelIdentity user, String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                channel.getApi().sendC2cMessage(user.getRawId(), text, "");
                return MessageResult.ok("");
            } catch (IOException e) {
                return MessageResult.fail(e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<MessageResult> sendGroupMessage(ChannelIdentity group, String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                channel.getApi().sendGroupMessage(group.getRawId(), text, "");
                return MessageResult.ok("");
            } catch (IOException e) {
                return MessageResult.fail(e.getMessage());
            }
        });
    }

    @Override
    public boolean isAvailable() { return true; }

    private String normalizeMarkdownImages(String text) {
        if (text == null || text.isBlank()) return text;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "!\\[[^\\]]*\\]\\(([^)]+)\\)").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "[IMAGE:url=" + java.util.regex.Matcher.quoteReplacement(matcher.group(1).trim()) + "]");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String convertMediaMarkers(String text, String target, boolean isGroup, String msgId) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\[(IMAGE|FILE):(?:url|path|file)=([^\\]]+)]").matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String type = matcher.group(1);
            String value = matcher.group(2);
            matcher.appendReplacement(result, "");
            if (value != null && !value.isBlank()) {
                try {
                    int fileType = "IMAGE".equalsIgnoreCase(type) ? 1 : 4;
                    String fileInfo;
                    if (isGroup) {
                        fileInfo = channel.getApi().uploadGroupFile(target, fileType, value.trim());
                        channel.getApi().sendGroupMedia(target, fileInfo, msgId);
                    } else {
                        fileInfo = channel.getApi().uploadC2cFile(target, fileType, value.trim());
                        channel.getApi().sendC2cMedia(target, fileInfo, msgId);
                    }
                } catch (IOException e) {
                    log.warn("Failed to send media {}", type, e);
                }
            }
        }
        matcher.appendTail(result);
        return result.toString().replaceAll("\n{3,}", "\n\n").trim();
    }
}
