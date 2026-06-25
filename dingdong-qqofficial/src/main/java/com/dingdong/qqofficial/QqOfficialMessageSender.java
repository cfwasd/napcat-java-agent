package com.dingdong.qqofficial;

import com.dingdong.channel.api.ChannelIdentity;
import com.dingdong.channel.api.ChannelMessageTarget;
import com.dingdong.channel.api.MessageResult;
import com.dingdong.channel.api.MessageSender;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * QQ 官方通道的 MessageSender 实现。
 */
@Slf4j
public class QqOfficialMessageSender implements MessageSender {

    private final QqOfficialApi api;
    private final QqOfficialIdMapper idMapper;
    private final QqOfficialProperties properties;

    public QqOfficialMessageSender(QqOfficialApi api, QqOfficialIdMapper idMapper,
                                   QqOfficialProperties properties) {
        this.api = api;
        this.idMapper = idMapper;
        this.properties = properties;
    }

    @Override
    public CompletableFuture<MessageResult> reply(String text) {
        return CompletableFuture.completedFuture(MessageResult.fail("reply() requires context"));
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
                api.sendC2cMessage(user.getRawId(), text, "");
                return MessageResult.ok("");
            } catch (IOException e) {
                log.warn("Failed to send private message to {}", user.getRawId(), e);
                return MessageResult.fail(e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<MessageResult> sendGroupMessage(ChannelIdentity group, String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                api.sendGroupMessage(group.getRawId(), text, "");
                return MessageResult.ok("");
            } catch (IOException e) {
                log.warn("Failed to send group message to {}", group.getRawId(), e);
                return MessageResult.fail(e.getMessage());
            }
        });
    }

    @Override
    public boolean isAvailable() { return true; }
}
