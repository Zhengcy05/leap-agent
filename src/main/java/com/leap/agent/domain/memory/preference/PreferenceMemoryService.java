package com.leap.agent.domain.memory.preference;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.MemoryProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局偏好记忆。
 */
@Service
public class PreferenceMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(PreferenceMemoryService.class);
    private static final String OWNER_ID = "leap-agent";
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SERVICE_PATTERN = Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9-]*-service)\\b");
    private static final Pattern PREFERENCE_SIGNAL_PATTERN = Pattern.compile(
            "以后|默认|请始终|务必|通常|习惯|偏好|风格|语言|地域|区域|时间范围|近\\s*(1|24|7|30|一|二十四|七|三十)\\s*(小时|天)|ap-[a-z-]+",
            Pattern.CASE_INSENSITIVE
    );

    private final PreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;
    private final Map<String, PreferenceEntry> preferences = new ConcurrentHashMap<>();
    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "preference-llm-extractor");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    public PreferenceMemoryService(PreferenceRepository preferenceRepository,
                                   ObjectMapper objectMapper,
                                   MemoryProperties memoryProperties) {
        this.preferenceRepository = preferenceRepository;
        this.objectMapper = objectMapper;
        this.memoryProperties = memoryProperties;
    }

    @PostConstruct
    public void loadPersistedPreferences() {
        // 运行期以进程内缓存为准，启动时只需要回放一次持久化快照。
        mergePersistedEntries(preferenceRepository.loadAll(OWNER_ID), PreferenceSource.BOOTSTRAP);
        logger.info("已加载全局偏好 {} 项", preferences.size());
    }

    /**
     * 同步规则抽取，保证本轮请求立刻生效。
     */
    public Map<String, String> applyRuleBasedPreferences(String message) {
        Map<String, String> extracted = extractRuleBasedPreferences(message);
        persistPreferences(extracted, PreferenceSource.RULE);
        return extracted;
    }

    /**
     * 回复完成后异步进行补充抽取。
     */
    public void extractPreferencesAsync(String message, Map<String, String> ruleBasedExtracted) {
        if (!memoryProperties.getPreference().isAsyncLlmEnabled()) {
            return;
        }
        if (message == null || message.isBlank()) {
            return;
        }
        if (dashScopeApiKey == null || dashScopeApiKey.isBlank() || dashScopeApiKey.contains("your-api-key")) {
            logger.debug("未配置有效 DashScope API Key，跳过异步偏好抽取");
            return;
        }
        if (!shouldTriggerAsyncExtraction(message, ruleBasedExtracted)) {
            logger.debug("当前消息缺少稳定偏好信号，跳过异步偏好抽取");
            return;
        }

        // 异步补充抽取只读取当前用户输入，避免把助手生成内容误写成稳定偏好。
        // 使用串行执行器保证写入顺序，避免高并发下同一份全局偏好被并发覆盖。
        extractionExecutor.execute(() -> {
            try {
                Map<String, String> extracted = extractPreferencesWithLlm(message);
                persistPreferences(extracted, PreferenceSource.LLM);
            } catch (Exception e) {
                logger.warn("异步偏好抽取失败: {}", e.getMessage());
            }
        });
    }

    public Map<String, String> snapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, PreferenceEntry> entry : snapshotEntries().entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().value());
        }
        return snapshot;
    }

    public Map<String, PreferenceEntry> snapshotEntries() {
        Map<String, PreferenceEntry> ordered = new LinkedHashMap<>();
        for (PreferenceKey key : PreferenceKey.values()) {
            PreferenceEntry entry = preferences.get(key.key());
            if (entry != null && entry.value() != null && !entry.value().isBlank()) {
                ordered.put(key.key(), entry);
            }
        }
        return ordered;
    }

    @PreDestroy
    public void shutdownExecutor() {
        extractionExecutor.shutdown();
    }

    private Map<String, String> extractRuleBasedPreferences(String message) {
        Map<String, String> extracted = new LinkedHashMap<>();
        if (message == null || message.isBlank()) {
            return extracted;
        }

        String normalizedMessage = message.trim();
        String lowerCaseMessage = normalizedMessage.toLowerCase(Locale.ROOT);

        if (normalizedMessage.contains("中文")) {
            extracted.put(PreferenceKey.REPLY_LANGUAGE.key(), "中文");
        } else if (lowerCaseMessage.contains("english") || normalizedMessage.contains("英文")) {
            extracted.put(PreferenceKey.REPLY_LANGUAGE.key(), "英文");
        }

        if (normalizedMessage.contains("简洁")) {
            extracted.put(PreferenceKey.REPLY_STYLE.key(), "简洁");
        } else if (normalizedMessage.contains("详细")) {
            extracted.put(PreferenceKey.REPLY_STYLE.key(), "详细");
        } else if (normalizedMessage.contains("专业")) {
            extracted.put(PreferenceKey.REPLY_STYLE.key(), "专业");
        } else if (normalizedMessage.contains("分点")) {
            extracted.put(PreferenceKey.REPLY_STYLE.key(), "分点");
        }

        String region = extractRegion(normalizedMessage);
        if (region != null) {
            extracted.put(PreferenceKey.CLS_REGION.key(), region);
        }

        String timeRange = extractTimeRange(normalizedMessage);
        if (timeRange != null) {
            extracted.put(PreferenceKey.TIME_RANGE.key(), timeRange);
        }

        String serviceScope = extractServiceScope(normalizedMessage);
        if (serviceScope != null) {
            extracted.put(PreferenceKey.SERVICE_SCOPE.key(), serviceScope);
        }

        String customRules = extractCustomRules(normalizedMessage);
        if (customRules != null) {
            extracted.put(PreferenceKey.CUSTOM_RULES.key(), customRules);
        }

        return extracted;
    }

    private Map<String, String> extractPreferencesWithLlm(String message) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();

        // 这里单独创建一个低温模型，只负责结构化抽取，不复用主对话 Agent 的上下文。
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.1)
                        .withMaxToken(600)
                        .withTopP(0.8)
                        .build())
                .build();

        String prompt = """
                请从下面这句用户消息中，提取对 oncallAgent 有长期价值的全局偏好或操作约定。
                只允许输出这些字段中的任意子集，且必须是 JSON 对象：
                - reply_language: 默认回复语言，例如 中文 / 英文
                - reply_style: 默认回复风格，例如 简洁 / 详细 / 专业 / 分点
                - cls_region: CLS 默认地域，必须是腾讯云地域编码，如 ap-guangzhou
                - time_range: 默认时间范围，例如 近1小时 / 近24小时 / 近7天 / 近一个月
                - service_scope: 默认服务范围，例如 order-service
                - custom_rules: 其他稳定规则，使用简短中文描述

                要求：
                1. 没有值的字段不要输出
                2. 只输出 JSON，不要解释，不要 markdown 代码块
                3. 如果消息只是一次性的临时请求，不要当成偏好输出

                用户消息：
                """ + message;

        ChatResponse response = chatModel.call(new Prompt(prompt));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return Map.of();
        }

        String raw = response.getResult().getOutput().getText();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }

        String cleaned = raw.trim().replace("```json", "").replace("```", "").trim();
        try {
            Map<String, String> parsed = objectMapper.readValue(cleaned, MAP_TYPE);
            Map<String, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                PreferenceKey.fromKey(entry.getKey())
                        .ifPresent(key -> {
                            String value = normalizeValue(key, entry.getValue());
                            if (value != null) {
                                normalized.put(key.key(), value);
                            }
                        });
            }
            return normalized;
        } catch (Exception e) {
            logger.warn("解析偏好抽取结果失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private void persistPreferences(Map<String, String> extracted, PreferenceSource source) {
        if (extracted == null || extracted.isEmpty()) {
            return;
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : extracted.entrySet()) {
            PreferenceKey.fromKey(entry.getKey()).ifPresent(key -> {
                String value = normalizeValue(key, entry.getValue());
                if (value != null) {
                    normalized.put(key.key(), value);
                }
            });
        }

        if (normalized.isEmpty()) {
            return;
        }

        // 先合并到内存缓存，再回写最终值；这样 custom_rules 这类追加型字段不会被覆盖掉。
        mergePreferences(normalized, source);
        normalized.keySet().forEach(key -> {
            PreferenceEntry persistedEntry = preferences.get(key);
            if (persistedEntry != null && persistedEntry.value() != null && !persistedEntry.value().isBlank()) {
                preferenceRepository.save(OWNER_ID, persistedEntry);
            }
        });
    }

    private void mergePreferences(Map<String, String> incoming, PreferenceSource source) {
        incoming.forEach((key, value) -> preferences.compute(key, (ignored, existing) -> mergeEntry(key, value, source, existing)));
    }

    private void mergePersistedEntries(Map<String, PreferenceEntry> incoming, PreferenceSource defaultSource) {
        incoming.forEach((key, entry) -> {
            PreferenceSource source = entry.source() == null ? defaultSource : entry.source();
            preferences.compute(key, (ignored, existing) -> mergeEntry(key, entry.value(), source, existing, entry.updatedAt(), entry.version()));
        });
    }

    private PreferenceEntry mergeEntry(String key, String incomingValue, PreferenceSource source, PreferenceEntry existing) {
        return mergeEntry(key, incomingValue, source, existing, System.currentTimeMillis(), -1L);
    }

    private PreferenceEntry mergeEntry(String key, String incomingValue, PreferenceSource source, PreferenceEntry existing,
                                       long incomingUpdatedAt, long incomingVersion) {
        if (incomingValue == null || incomingValue.isBlank()) {
            return existing;
        }

        String mergedValue = PreferenceKey.CUSTOM_RULES.key().equals(key)
                ? mergeCustomRules(existing != null ? existing.value() : null, incomingValue)
                : incomingValue;

        if (existing != null && mergedValue.equals(existing.value())) {
            return existing;
        }

        long updatedAt = incomingUpdatedAt > 0 ? incomingUpdatedAt : System.currentTimeMillis();
        long version = incomingVersion > 0 ? incomingVersion : (existing == null ? 1L : existing.version() + 1L);
        return new PreferenceEntry(key, mergedValue, source, updatedAt, version);
    }

    private String mergeCustomRules(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return incoming;
        }
        if (existing.contains(incoming)) {
            return existing;
        }
        return existing + "；" + incoming;
    }

    private String normalizeValue(PreferenceKey key, String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        return switch (key) {
            case REPLY_LANGUAGE -> normalizeLanguage(trimmed);
            case REPLY_STYLE -> normalizeStyle(trimmed);
            case CLS_REGION -> normalizeRegion(trimmed);
            case TIME_RANGE -> normalizeTimeRange(trimmed);
            case SERVICE_SCOPE, CUSTOM_RULES -> trimmed;
        };
    }

    private String normalizeLanguage(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("english") || value.contains("英文")) {
            return "英文";
        }
        return "中文";
    }

    private String normalizeStyle(String value) {
        if (value.contains("简洁")) {
            return "简洁";
        }
        if (value.contains("详细")) {
            return "详细";
        }
        if (value.contains("分点")) {
            return "分点";
        }
        return "专业";
    }

    private String normalizeRegion(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ap-")) {
            return lower;
        }
        return switch (value) {
            case "广州", "广州市" -> "ap-guangzhou";
            case "上海", "上海市" -> "ap-shanghai";
            case "北京", "北京市" -> "ap-beijing";
            case "南京", "南京市" -> "ap-nanjing";
            case "成都", "成都市" -> "ap-chengdu";
            default -> value;
        };
    }

    private String normalizeTimeRange(String value) {
        if (value.contains("1小时") || value.contains("一小时")) {
            return "近1小时";
        }
        if (value.contains("24小时") || value.contains("一天")) {
            return "近24小时";
        }
        if (value.contains("7天") || value.contains("一周")) {
            return "近7天";
        }
        if (value.contains("一个月") || value.contains("30天")) {
            return "近一个月";
        }
        return value;
    }

    private String extractRegion(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("ap-guangzhou") || message.contains("广州")) {
            return "ap-guangzhou";
        }
        if (lower.contains("ap-shanghai") || message.contains("上海")) {
            return "ap-shanghai";
        }
        if (lower.contains("ap-beijing") || message.contains("北京")) {
            return "ap-beijing";
        }
        if (lower.contains("ap-nanjing") || message.contains("南京")) {
            return "ap-nanjing";
        }
        if (lower.contains("ap-chengdu") || message.contains("成都")) {
            return "ap-chengdu";
        }
        return null;
    }

    private String extractTimeRange(String message) {
        if (message.contains("近1小时") || message.contains("最近1小时") || message.contains("近一小时")) {
            return "近1小时";
        }
        if (message.contains("近24小时") || message.contains("最近24小时") || message.contains("一天内")) {
            return "近24小时";
        }
        if (message.contains("近7天") || message.contains("最近7天") || message.contains("一周内")) {
            return "近7天";
        }
        if (message.contains("近一个月") || message.contains("最近一个月") || message.contains("近30天")) {
            return "近一个月";
        }
        return null;
    }

    private String extractServiceScope(String message) {
        Matcher matcher = SERVICE_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractCustomRules(String message) {
        if (message.contains("以后") || message.contains("默认") || message.contains("务必") || message.contains("请始终")) {
            return message;
        }
        return null;
    }

    private boolean shouldTriggerAsyncExtraction(String message, Map<String, String> ruleBasedExtracted) {
        if (ruleBasedExtracted != null && !ruleBasedExtracted.isEmpty()) {
            return true;
        }

        String normalizedMessage = message.trim();
        if (normalizedMessage.length() > 120) {
            return false;
        }

        return PREFERENCE_SIGNAL_PATTERN.matcher(normalizedMessage).find()
                || SERVICE_PATTERN.matcher(normalizedMessage).find();
    }
}
