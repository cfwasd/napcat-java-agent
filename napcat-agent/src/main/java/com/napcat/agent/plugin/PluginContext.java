package com.napcat.agent.plugin;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 插件上下文。
 */
@Getter
@AllArgsConstructor
public class PluginContext {
    private final String agentId;
    private final Object config;
}
