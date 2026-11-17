package com.beastmouth.agentkit.agent;

import com.beastmouth.agentkit.support.ErrorCode;

/**
 * 流式输出回调。实现方负责把这些事件转成 SSE 帧。
 *
 * <p>四类事件对应前端四处 UI：状态行（理解中/执行中）、工具调用条、正文气泡、错误条。
 * 回调都在请求线程里同步触发，实现方不需要考虑并发。</p>
 *
 * <h3>为什么 {@link #error} 是 default 方法</h3>
 * <p>这是后加的第四帧。改成抽象方法会让所有既有实现（业务侧自己写的 SSE sink、
 * 框架内部的装饰器、测试里的假 sink）编译失败——升级脚手架不该要求改业务代码。
 * 默认空实现意味着「不关心错误帧的调用方」行为完全不变：异常照旧上抛，
 * 由 Controller 的异常处理器兜底。</p>
 */
public interface ChatSink {

    /** 阶段提示，例如 routing（正在理解）、tool（正在执行） */
    void status(String stage, String label);

    /** 一次工具调用的结果 */
    void tool(String name, boolean success, String summary);

    /** 正文增量 */
    void delta(String text);

    /**
     * 这一轮已经产出过正文，但<b>中途失败了</b>：把错误作为一帧推给前端，再由调用方正常收尾。
     *
     * <p>存在的理由：流式一旦开始，HTTP 200 和响应头就已经发出去了。此时再抛异常，
     * 连接会直接断开，前端拿到的是「网络错误」——比错误本身更难排查，而且
     * <b>已经流出去的那截正文会从界面和库里一起消失</b>（用户看到了半句话，刷新后连半句都没有）。
     * 推一帧 error 再正常结束，前端可以保留已显示内容、在下面补一行错误提示，
     * 库里落的是真实产出过的部分。</p>
     *
     * @param code      错误码，见 {@link ErrorCode}
     * @param message   给用户看的一句话：发生了什么
     * @param retryable 重试是否可能成功，前端据此决定显示「重试」还是「联系管理员」
     * @param hint      下一步建议，可直接渲染
     */
    default void error(String code, String message, boolean retryable, String hint) {
    }

    /** 便捷重载：直接吃错误码，业务侧不用把四个字段拆开传 */
    default void error(ErrorCode code) {
        if (code != null) {
            error(code.code(), code.message(), code.retryable(), code.hint());
        }
    }

    /** 空实现，便于测试与非流式调用 */
    ChatSink NOOP = new ChatSink() {
        @Override
        public void status(String stage, String label) {
        }

        @Override
        public void tool(String name, boolean success, String summary) {
        }

        @Override
        public void delta(String text) {
        }
    };
}
