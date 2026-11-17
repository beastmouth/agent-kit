package com.beastmouth.agentkit.support;

/**
 * 脚手架内部抛出的运行时异常。
 *
 * <p>继承 {@link RuntimeException} 而不是受检异常：调用链上九成位置都在框架回调里，
 * 受检异常会逼着每一层写无意义的 try/catch。</p>
 *
 * <p>携带 {@link ErrorCode}：上层据此决定「重试 / 降级 / 直接失败」，不必去解析 message 文本。</p>
 */
public class AgentKitException extends RuntimeException {

    private final ErrorCode errorCode;

    public AgentKitException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentKitException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public static AgentKitException of(ErrorCode errorCode) {
        return new AgentKitException(errorCode, errorCode.message());
    }

    public static AgentKitException of(ErrorCode errorCode, String message) {
        return new AgentKitException(errorCode, message);
    }

    public static AgentKitException of(ErrorCode errorCode, String message, Throwable cause) {
        return new AgentKitException(errorCode, message, cause);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 便于日志与指标直接取用，形如 {@code MODEL_UNAVAILABLE: 模型渠道不可用} */
    @Override
    public String toString() {
        return errorCode.code() + ": " + getMessage();
    }
}
