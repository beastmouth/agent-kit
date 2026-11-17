package com.beastmouth.agentkit.context;

/**
 * 一轮对话的生命周期钩子。
 *
 * <p>有些准备工作天然属于「一轮」而不是「一次工具调用」：预取本轮要用的数据、
 * 开启/关闭业务侧的作用域、在入口埋自定义指标、清掉上一轮残留的临时状态。
 * 这些时机只有执行器知道，但具体做什么只有业务知道，所以留成钩子。</p>
 *
 * <p><b>与 {@link com.beastmouth.agentkit.tool.ToolInterceptor} 的分工</b>：
 * 这个是「每轮一次」，那个是「每次工具调用一次」。别把它们混用——在每轮钩子里做
 * 逐工具校验会漏（模型一轮可能调 3 个工具），在工具拦截器里做每轮初始化会重入。</p>
 *
 * <p>实现必须<b>永不抛异常</b>：钩子挂掉不能连累对话。默认就是 {@link #NOOP}，
 * 不实现也能跑。</p>
 */
public interface TurnHook {

    /**
     * 一轮开始。
     *
     * @param userMessage 用户本轮原话
     */
    default void onTurnStart(String userMessage) {
    }

    /** 一轮结束，<b>异常路径也会调用</b>，实现必须容忍未 start 就被调用 */
    default void onTurnEnd() {
    }

    TurnHook NOOP = new TurnHook() {
    };
}
