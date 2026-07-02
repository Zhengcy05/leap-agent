package com.leap.agent.runtime.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 统一管理 Agent 可用工具，包括本地方法工具和 MCP 动态工具。
 */
@Component
public class AgentToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final DateTimeTools dateTimeTools;
    private final InternalDocsTools internalDocsTools;
    private final QueryMetricsTools queryMetricsTools;
    private final QueryLogsTools queryLogsTools;
    private final ToolCallbackProvider toolCallbackProvider;

    public AgentToolRegistry(DateTimeTools dateTimeTools,
                             InternalDocsTools internalDocsTools,
                             QueryMetricsTools queryMetricsTools,
                             ObjectProvider<QueryLogsTools> queryLogsToolsProvider,
                             ToolCallbackProvider toolCallbackProvider) {
        this.dateTimeTools = dateTimeTools;
        this.internalDocsTools = internalDocsTools;
        this.queryMetricsTools = queryMetricsTools;
        this.queryLogsTools = queryLogsToolsProvider.getIfAvailable();
        this.toolCallbackProvider = toolCallbackProvider;
    }

    /**
     * 构建基于反射注入的本地方法工具列表。
     */
    public Object[] getMethodTools() {
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        }
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
    }

    /**
     * 获取 MCP 等外部扩展工具的回调列表。
     */
    public ToolCallback[] getToolCallbacks() {
        return toolCallbackProvider.getToolCallbacks();
    }

    /**
     * 输出当前可用的外部工具名称，方便排查 MCP 连接和工具发现问题。
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }
}
