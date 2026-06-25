package com.dingdong.qqofficial.event;

import com.dingdong.channel.api.ChannelMessageEvent;
import lombok.Getter;
import lombok.Setter;

/**
 * QQ 官方 C2C 私聊消息事件。
 */
@Getter
@Setter
public class QqOfficialPrivateMessageEvent extends ChannelMessageEvent {
    private String userOpenid;
    private String msgId;
}
