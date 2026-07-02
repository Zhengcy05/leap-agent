package com.leap.agent.common.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 清空会话历史请求体。
 */
@Setter
@Getter
public class ClearRequest {

    @JsonProperty(value = "Id")
    @JsonAlias({"id", "ID"})
    private String Id;
}
