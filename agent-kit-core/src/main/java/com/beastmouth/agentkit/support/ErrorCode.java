package com.beastmouth.agentkit.support;

/**
 * 脚手架统一的错误码。
 *
 * <p>只保留「谁能处理」这个维度：调用方关心的是「重试有没有用」，而不是内部编号。
 * 编号固定成字符串，日志里可读、指标里可直接当标签。</p>
 *
 * <h3>为什么每个码要带一句话的 hint</h3>
 * <p>错误提示是唯一「用户与服务端同时看见」的文本。只给 {@code MODEL_UNAVAILABLE} 这种码，
 * 前端只能干巴巴弹一句「服务异常」；只给堆栈，用户看不懂还泄露实现。所以这里统一提供三样：
 * {@code message}（发生了什么）、{@code retryable}（要不要让用户再试一次）、
 * {@code hint}（他能做点什么）。前端照着渲染即可，不必自己维护一张映射表——
 * 那种表迟早和后端不同步。</p>
 */
public enum ErrorCode {

    /** 模型渠道不可用：未配置、渠道不存在、额度耗尽、限流、网络超时。可降级、可重试 */
    MODEL_UNAVAILABLE("MODEL_UNAVAILABLE", "模型服务暂时不可用", true,
            "稍等几秒再发一次；连续失败请联系管理员检查渠道配置与额度"),

    /** Agent 总开关关闭 */
    AGENT_DISABLED("AGENT_DISABLED", "Agent 未启用", false,
            "这是服务端开关（agent-kit.enabled），请联系管理员"),

    /** 模型返回的内容不是约定的协议结构，重试有意义 */
    PROTOCOL_PARSE_FAILED("PROTOCOL_PARSE_FAILED", "模型输出不符合协议", true,
            "换个说法重发一次通常就好；频繁出现说明提示词与当前模型能力不匹配"),

    /** 配置缺失或非法，重试无用 */
    INVALID_CONFIG("INVALID_CONFIG", "配置不合法", false,
            "这是服务端配置问题，重试无用，请联系管理员"),

    /** 工具执行失败，通常由业务自行处理 */
    TOOL_FAILED("TOOL_FAILED", "工具执行失败", true,
            "确认参数是否合理后重试；持续失败说明工具实现有问题"),

    /** 会话数量达到上限 */
    SESSION_LIMIT("SESSION_LIMIT", "会话数量已达上限", false,
            "删掉一些不再需要的会话，或新建一个会话继续"),

    /** 会话不存在或不属于当前用户 */
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", "会话不存在", false,
            "刷新会话列表后重新打开；刚被删除的会话无法恢复"),

    /**
     * 会话正被另一轮对话占着（同一会话同时只允许跑一轮）。
     *
     * <p>与 {@link #SESSION_READONLY} 的区别只有一个：这个能重试，那个不能。
     * 早期版本两者共用 {@code SESSION_READONLY}，前端根本没法判断该不该让用户重发。</p>
     */
    SESSION_BUSY("SESSION_BUSY", "该会话正在处理上一条消息", true,
            "等上一条回答结束再发；前端连点「发送」也会看到这条"),

    /** 会话只读（已归档/已结束） */
    SESSION_READONLY("SESSION_READONLY", "会话只读", false,
            "该会话已结束或归档，请新建会话继续"),

    /** 会话标题不合法 */
    SESSION_TITLE_INVALID("SESSION_TITLE_INVALID", "会话标题不合法", false,
            "标题去掉首尾空白，长度控制在 1~64 字之间"),

    /**
     * 未归类的服务端异常。
     *
     * <p>只用于「已经到了必须回一帧/一个响应体，但原因是意料之外」的场合，比如流式中途
     * 空指针。文案刻意不透露任何实现细节——{@code e.getMessage()} 常常是类名或 SQL 片段，
     * 用户看不懂、攻击者爱看，原文只进日志。</p>
     *
     * <p>可重试：这类错误多半是瞬时的。若实际不可重试，用户重试两次也会自己放弃，
     * 比「提示不可重试但其实超时了」让用户直接去找客服要好。</p>
     */
    INTERNAL_ERROR("INTERNAL_ERROR", "服务出了一点问题", true,
            "稍后重试一次；反复出现请把时间点告诉管理员，方便按日志排查");

    private final String code;
    private final String message;
    private final boolean retryable;
    private final String hint;

    ErrorCode(String code, String message, boolean retryable, String hint) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
        this.hint = hint;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** 调用方重试是否可能成功：前端据此决定显示「重试」还是「联系管理员」 */
    public boolean retryable() {
        return retryable;
    }

    /** 给用户看的下一步建议，可直接渲染 */
    public String hint() {
        return hint;
    }
}
