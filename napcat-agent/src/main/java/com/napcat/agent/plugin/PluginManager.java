package com.napcat.agent.plugin;

import com.dingdong.channel.api.ChannelMessageEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件管理器。
 */
@Slf4j
public class PluginManager {

    private final List<AgentPlugin> plugins = new CopyOnWriteArrayList<>();

    public void register(AgentPlugin plugin) {
        plugins.add(plugin);
        log.info("Plugin registered: {}", plugin.pluginId());
    }

    public void unregister(String pluginId) {
        plugins.removeIf(p -> p.pluginId().equals(pluginId));
    }

    public void loadAll(PluginContext context) {
        for (AgentPlugin plugin : plugins) {
            try {
                plugin.onLoad(context);
                log.info("Plugin loaded: {}", plugin.pluginId());
            } catch (Exception e) {
                log.warn("Failed to load plugin: {}", plugin.pluginId(), e);
            }
        }
    }

    public void unloadAll() {
        for (AgentPlugin plugin : plugins) {
            try {
                plugin.onUnload();
            } catch (Exception e) {
                log.warn("Failed to unload plugin: {}", plugin.pluginId(), e);
            }
        }
        plugins.clear();
    }

    public String collectSystemPromptExtensions() {
        StringBuilder sb = new StringBuilder();
        for (AgentPlugin plugin : plugins) {
            String ext = plugin.getSystemPromptExtension();
            if (ext != null && !ext.isBlank()) {
                sb.append("\n").append(ext);
            }
        }
        return sb.toString();
    }

    public boolean notifyMessage(ChannelMessageEvent event) {
        for (AgentPlugin plugin : plugins) {
            try {
                if (plugin.onMessage(event)) return true;
            } catch (Exception e) {
                log.warn("Plugin message handler error: {}", plugin.pluginId(), e);
            }
        }
        return false;
    }
}
