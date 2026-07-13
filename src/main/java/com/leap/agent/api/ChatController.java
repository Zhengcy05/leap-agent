package com.leap.agent.api;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.leap.agent.common.model.ApiResponse;
import com.leap.agent.common.model.ChatRequest;
import com.leap.agent.common.model.ChatResponse;
import com.leap.agent.common.model.ClearRequest;
import com.leap.agent.common.model.MemoryDebugResponse;
import com.leap.agent.common.model.SessionInfoResponse;
import com.leap.agent.domain.aiops.AiOpsService;
import com.leap.agent.domain.chat.ChatSessionService;
import com.leap.agent.domain.chat.ChatService;
import com.leap.agent.domain.memory.preference.PreferenceMemoryService;
import com.leap.agent.domain.memory.shortterm.ShortTermMemorySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;
    
    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private PreferenceMemoryService preferenceMemoryService;

    @Autowired
    private SseEventSender sseEventSender;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            // 获取或创建会话
            ChatSessionService.ChatSession session = chatSessionService.getOrCreateSession(request.getId());

            // 同步规则抽取，保证本轮 prompt 立即生效
            Map<String, String> ruleBasedPreferences = preferenceMemoryService.applyRuleBasedPreferences(request.getQuestion());

            // 获取历史消息
            ShortTermMemorySnapshot history = session.getHistorySnapshot();
            logger.info("会话历史消息对数: {}", history.messagePairCount());

            // 创建 DashScope API 和 ChatModel
            DashScopeApi dashScopeApi = chatService.createDashScopeApi();
            DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            // 构建系统提示词（包含历史消息）
            String systemPrompt = chatService.buildSystemPrompt(
                    history,
                    preferenceMemoryService.snapshot(),
                    preferenceMemoryService.promptPreferenceItems()
            );
            
            // 创建 ReactAgent
            ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
            
            // 执行对话
            String fullAnswer = chatService.executeChat(agent, request.getQuestion());
            
            // 更新会话历史
            session.addMessage(request.getQuestion(), fullAnswer);
            preferenceMemoryService.extractPreferencesAsync(request.getQuestion(), ruleBasedPreferences);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                request.getId(), session.getMessagePairCount());
            
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            return chatSessionService.getSession(request.getId())
                    .map(session -> {
                        session.clearHistory();
                        return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
                    })
                    .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("会话不存在")));

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                sseEventSender.sendError(emitter, "问题内容不能为空");
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 异步执行
        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 获取或创建会话
                ChatSessionService.ChatSession session = chatSessionService.getOrCreateSession(request.getId());

                // 同步规则抽取，保证本轮 prompt 立即生效
                Map<String, String> ruleBasedPreferences = preferenceMemoryService.applyRuleBasedPreferences(request.getQuestion());

                // 获取历史消息
                ShortTermMemorySnapshot history = session.getHistorySnapshot();
                logger.info("ReactAgent 会话历史消息对数: {}", history.messagePairCount());

                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(
                        history,
                        preferenceMemoryService.snapshot(),
                        preferenceMemoryService.promptPreferenceItems()
                );
                
                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());

                // 订阅并监听数据流
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        sseEventSender.sendContent(emitter, chunk);
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            sseEventSender.sendError(emitter, error.getMessage());
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            // 更新会话历史
                            session.addMessage(request.getQuestion(), fullAnswer);
                            preferenceMemoryService.extractPreferencesAsync(request.getQuestion(), ruleBasedPreferences);
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                                request.getId(), session.getMessagePairCount());
                            
                            // 发送完成标记
                            sseEventSender.sendDone(emitter);
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    sseEventSender.sendError(emitter, e.getMessage());
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = DashScopeChatModel.builder()
                        .dashScopeApi(dashScopeApi)
                        .defaultOptions(DashScopeChatOptions.builder()
                                .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                                .withTemperature(0.3)
                                .withMaxToken(8000)
                                .withTopP(0.9)
                                .build())
                        .build();

                sseEventSender.sendContent(emitter, "正在读取告警并拆解任务...\n");
                
                // 调用 AiOpsService 执行分析流程
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel);

                if (overAllStateOptional.isEmpty()) {
                    sseEventSender.sendError(emitter, "多 Agent 编排未获取到有效结果");
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());
                    
                    // 发送分隔线
                    sseEventSender.sendContent(emitter, "\n\n" + "=".repeat(60) + "\n");
                    
                    // 发送完整的告警分析报告
                    sseEventSender.sendContent(emitter, "📋 **告警分析报告**\n\n");
                    
                    sseEventSender.sendContentInChunks(emitter, finalReportText, 50);
                    
                    // 发送结束分隔线
                    sseEventSender.sendContent(emitter, "\n" + "=".repeat(60) + "\n\n");
                    
                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    sseEventSender.sendContent(emitter, "⚠️ 多 Agent 流程已完成，但未能生成最终报告。");
                }

                sseEventSender.sendDone(emitter);
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    sseEventSender.sendError(emitter, "AI Ops 流程失败: " + e.getMessage());
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            Optional<ChatSessionService.ChatSession> sessionOptional = chatSessionService.getSession(sessionId);
            if (sessionOptional.isPresent()) {
                ChatSessionService.ChatSession session = sessionOptional.get();
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.getCreateTime());
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 查看短期记忆与全局偏好快照。
     */
    @GetMapping("/chat/memory")
    public ResponseEntity<ApiResponse<MemoryDebugResponse>> getMemoryDebugInfo(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            logger.info("收到获取记忆调试信息请求 - SessionId: {}", sessionId);

            // 默认先返回所有活动 session 的摘要；只有明确指定 sessionId 时才展开明细。
            List<MemoryDebugResponse.SessionMemorySummary> sessions = chatSessionService.listSessionSnapshots().stream()
                    .map(snapshot -> new MemoryDebugResponse.SessionMemorySummary(
                            snapshot.sessionId(),
                            snapshot.memory().messagePairCount(),
                            snapshot.createTime()))
                    .toList();

            MemoryDebugResponse.SessionMemoryDetail sessionDetail = null;
            if (sessionId != null && !sessionId.isBlank()) {
                Optional<ChatSessionService.ChatSession> sessionOptional = chatSessionService.getSession(sessionId);
                if (sessionOptional.isEmpty()) {
                    return ResponseEntity.ok(ApiResponse.error("会话不存在"));
                }

                ChatSessionService.ChatSessionSnapshot snapshot = sessionOptional.get().snapshot();
                sessionDetail = new MemoryDebugResponse.SessionMemoryDetail(
                        snapshot.sessionId(),
                        snapshot.memory().messagePairCount(),
                        snapshot.createTime(),
                        snapshot.memory().messages());
            }

            MemoryDebugResponse response = new MemoryDebugResponse(
                    preferenceMemoryService.snapshot(),
                    preferenceMemoryService.snapshotEntries().entrySet().stream()
                            .collect(LinkedHashMap::new,
                                    (map, entry) -> map.put(entry.getKey(), MemoryDebugResponse.PreferenceEntryView.from(entry.getValue())),
                                    LinkedHashMap::putAll),
                    preferenceMemoryService.snapshotPreferenceItems().stream()
                            .map(MemoryDebugResponse.PreferenceItemView::from)
                            .toList(),
                    sessions,
                    sessionDetail
            );
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("获取记忆调试信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

}
