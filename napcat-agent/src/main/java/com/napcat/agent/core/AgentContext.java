package com.napcat.agent.core;

import com.dingdong.channel.api.ChannelMessageEvent;
import com.napcat.agent.session.Session;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 单次 Agent 对话上下文。
 */
@Getter
@Setter
public class AgentContext {
    private ChannelMessageEvent triggerEvent;
    private Session session;
    private String systemPrompt;
    private int round;
    private List<String> tools;
    private boolean visionEnabled;
}
