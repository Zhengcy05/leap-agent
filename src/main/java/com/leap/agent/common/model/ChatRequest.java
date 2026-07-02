package com.leap.agent.common.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 聊天接口请求体，兼容前端传入的不同大小写字段名。
 */
@Setter
@Getter
public class ChatRequest {

    @JsonProperty(value = "Id")
    @JsonAlias({"id", "ID"})
    private String Id;

    @JsonProperty(value = "Question")
    @JsonAlias({"question", "QUESTION"})
    private String Question;
}
