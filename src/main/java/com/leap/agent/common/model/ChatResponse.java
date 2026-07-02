package com.leap.agent.common.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 普通聊天接口响应体。
 */
@Setter
@Getter
public class ChatResponse {

    private boolean success;
    private String answer;
    private String errorMessage;

    public static ChatResponse success(String answer) {
        ChatResponse response = new ChatResponse();
        response.setSuccess(true);
        response.setAnswer(answer);
        return response;
    }

    public static ChatResponse error(String errorMessage) {
        ChatResponse response = new ChatResponse();
        response.setSuccess(false);
        response.setErrorMessage(errorMessage);
        return response;
    }
}
