package com.leap.agent.common.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 会话状态查询响应体。
 */
@Setter
@Getter
public class SessionInfoResponse {

    private String sessionId;
    private int messagePairCount;
    private long createTime;
}
