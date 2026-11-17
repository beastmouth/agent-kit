package com.beastmouth.agentkit.agent;

/**
 * 流式输出回调。实现方负责把这些事件转成 SSE 帧。
 *
 * <p>三类事件对应前端的三处 UI：状态行（理解中/执行中）、工具调用条、正文气泡。
 * 回调都在请求线程里同步触发，实现方不需要考虑并发。</p>
 */
public interface ChatSink {

    /** 阶段提示，例如 routing（正在理解）、tool（正在执行） */
    void status(String stage, String label);

    /** 一次工具调用的结果 */
    void tool(String name, boolean success, String summary);

    /** 正文增量 */
    void delta(String text);

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
