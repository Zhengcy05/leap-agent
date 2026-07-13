package com.leap.agent.domain.chat;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.leap.agent.domain.memory.preference.PreferenceItem;
import com.leap.agent.domain.memory.preference.PreferenceKey;
import com.leap.agent.domain.memory.shortterm.ShortTermMemorySnapshot;
import com.leap.agent.runtime.tool.AgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.List;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private AgentToolRegistry agentToolRegistry;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数-把低概率的词语直接扔进垃圾桶
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param shortTermMemory 短期记忆快照
     * @param preferences 全局偏好快照
     * @param preferenceItems 开放偏好条目
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(ShortTermMemorySnapshot shortTermMemory,
                                    Map<String, String> preferences,
                                    List<PreferenceItem> preferenceItems) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        // 先固定助手职责和工具边界，再注入偏好与短期记忆，减少模型在默认行为上的漂移。
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询；若【全局偏好】未指定，默认查询地域 ap-guangzhou，默认查询时间范围为近一个月。\n\n");

        appendPreferenceSection(systemPromptBuilder, preferences);
        appendPreferenceItemSection(systemPromptBuilder, preferenceItems);

        if (shortTermMemory != null) {
            String historySection = shortTermMemory.renderPromptSection();
            if (!historySection.isBlank()) {
                systemPromptBuilder.append(historySection).append("\n\n");
            }
        }

        systemPromptBuilder.append("请优先遵守【全局偏好】与【行为偏好 / 经验约定】，并结合【短期记忆 / 对话历史】回答用户的新问题。");

        return systemPromptBuilder.toString();
    }

    private void appendPreferenceSection(StringBuilder builder, Map<String, String> preferences) {
        if (preferences == null || preferences.isEmpty()) {
            return;
        }
        builder.append("【全局偏好】\n");
        for (PreferenceKey key : PreferenceKey.values()) {
            String value = preferences.get(key.key());
            if (value != null && !value.isBlank()) {
                builder.append("- ").append(key.displayName()).append(": ").append(value).append("\n");
            }
        }
        builder.append("\n");
    }

    private void appendPreferenceItemSection(StringBuilder builder, List<PreferenceItem> preferenceItems) {
        if (preferenceItems == null || preferenceItems.isEmpty()) {
            return;
        }

        // 这里接收的是记忆服务筛选后的 Top N，避免开放偏好无限膨胀进 system prompt。
        builder.append("【行为偏好 / 经验约定】\n");
        for (PreferenceItem item : preferenceItems) {
            builder.append("- ")
                    .append(item.content())
                    .append("（")
                    .append(item.category())
                    .append(" / ")
                    .append(item.scope())
                    .append("）\n");
        }
        builder.append("\n");
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        agentToolRegistry.logAvailableTools();
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(agentToolRegistry.getMethodTools())
                // 基于反射注入
                .tools(agentToolRegistry.getToolCallbacks())
                // 通过网络动态拉取过来的MCP外部扩展能力
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
