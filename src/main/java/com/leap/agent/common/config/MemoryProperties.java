package com.leap.agent.common.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆模块配置。
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    // 短期记忆和偏好记忆先分别建配置块，后续接入长期记忆时可继续沿用同一前缀。
    private final ShortTerm shortTerm = new ShortTerm();
    private final Preference preference = new Preference();

    @Getter
    public static class ShortTerm {
        /**
         * 短期记忆保留的最大消息对数。
         */
        private int maxWindowSize = 6;

        public void setMaxWindowSize(int maxWindowSize) {
            this.maxWindowSize = maxWindowSize;
        }
    }

    @Getter
    public static class Preference {
        /**
         * 偏好持久化文件路径。
         * 当前默认是本地 JSON 文件，后续若切数据库可保留该配置作为本地开发兜底。
         */
        private String storagePath = "./data/preferences.json";

        /**
         * 是否启用回复后的异步 LLM 偏好抽取。
         */
        private boolean asyncLlmEnabled = true;

        public void setStoragePath(String storagePath) {
            this.storagePath = storagePath;
        }

        public void setAsyncLlmEnabled(boolean asyncLlmEnabled) {
            this.asyncLlmEnabled = asyncLlmEnabled;
        }
    }
}
