package com.beastmouth.agentkit.support;

/**
 * 脚手架统一的错误码。
 *
 * <p>只保留「谁能处理」这个维度：调用方关心的是「重试有没有用」，而不是内部编号。
 * 编号固定成字符串，日志里可读、指标里可直接当标签。</p>
 */
public enum ErrorCode {

    /** 模型渠道不可用：未配置、渠道不存在、额度耗尽、限流。可降级、可重试 */
    MODEL_UNAVAILABLE("MODEL_UNAVAILABLE", "模型渠道不可用"),

    /** Agent 总开关关闭 */
    AGENT_DISABLED("AGENT_DISABLED", "Agent 未启用"),

    /** 模型返回的内容不是约定的协议结构，重试有意义 */
    PROTOCOL_PARSE_FAILED("PROTOCOL_PARSE_FAILED", "模型输出不符合协议"),

    /** 配置缺失或非法，重试无用 */
    INVALID_CONFIG("INVALID_CONFIG", "配置不合法"),

    /** 工具执行失败，通常由业务自行处理 */
    TOOL_FAILED("TOOL_FAILED", "工具执行失败"),

    /** 会话数量达到上限 */
    SESSION_LIMIT("SESSION_LIMIT", "会话数量已达上限"),

    /** 会话不存在或不属于当前用户 */
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", "会话不存在"),

    /** 会话只读（已归档/已结束） */
    SESSION_READONLY("SESSION_READONLY", "会话只读"),

    /** 会话标题不合法 */
    SESSION_TITLE_INVALID("SESSION_TITLE_INVALID", "会话标题不合法");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
