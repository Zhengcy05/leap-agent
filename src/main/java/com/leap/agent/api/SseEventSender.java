package com.leap.agent.api;

import com.leap.agent.common.model.SseMessage;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 封装 SSE 事件发送细节，让 Controller 只关注发送什么内容。
 */
@Component
public class SseEventSender {

    private static final String MESSAGE_EVENT = "message";

    public void sendContent(SseEmitter emitter, String data) throws IOException {
        send(emitter, SseMessage.content(data));
    }

    public void sendError(SseEmitter emitter, String errorMessage) throws IOException {
        send(emitter, SseMessage.error(errorMessage));
    }

    public void sendDone(SseEmitter emitter) throws IOException {
        send(emitter, SseMessage.done());
    }

    /**
     * 将长文本拆成小块发送，避免前端等待完整内容后才更新。
     */
    public void sendContentInChunks(SseEmitter emitter, String data, int chunkSize) throws IOException {
        for (int i = 0; i < data.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, data.length());
            sendContent(emitter, data.substring(i, end));
        }
    }

    private void send(SseEmitter emitter, SseMessage message) throws IOException {
        emitter.send(SseEmitter.event()
                .name(MESSAGE_EVENT)
                .data(message, MediaType.APPLICATION_JSON));
    }
}
