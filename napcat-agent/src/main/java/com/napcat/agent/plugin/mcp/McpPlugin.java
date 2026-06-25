package com.napcat.agent.plugin.mcp;

import com.napcat.agent.plugin.AgentPlugin;
import com.napcat.agent.plugin.PluginContext;
import com.napcat.agent.tool.ToolSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * MCP 插件骨架（预留）。
 * TODO: MCP 支持（预留接口，待开发）
 */
@Slf4j
public class McpPlugin implements AgentPlugin {

    @Override
    public String pluginId() { return "mcp"; }

    @Override
    public void onLoad(PluginContext ctx) {
        log.info("MCP plugin loaded (placeholder)");
    }

    @Override
    public void onUnload() {
        log.info("MCP plugin unloaded");
    }

    @Override
    public List<ToolSchema> getTools() {
        return List.of();
    }

    @Override
    public String getSystemPromptExtension() {
        return "";
    }
}
