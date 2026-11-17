package com.beastmouth.agentkit.example.support;

import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.context.TurnHook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 「每轮对话前后要做什么」这一层适配。
 *
 * <p>有些准备工作天然属于「一轮」而不是「一次工具调用」：预取本轮要用的业务数据、
 * 开启/关闭业务侧的作用域、给本轮打一个自定义指标、清掉上一轮残留的临时状态。
 * 这些时机只有执行器知道，具体做什么只有业务知道，所以留成钩子。</p>
 *
 * <h3>和 {@code ToolInterceptor} 的分工（最容易搞混的一点）</h3>
 * <table border="1">
 *   <tr><th></th><th>触发次数</th><th>典型用途</th></tr>
 *   <tr><td>{@link TurnHook}</td><td>每轮 1 次</td><td>预取、作用域开关、每轮指标</td></tr>
 *   <tr><td>{@code ToolInterceptor}</td><td>每个工具 1 次</td><td>鉴权、审计、限流、脱敏</td></tr>
 * </table>
 * <p>把逐工具校验写进每轮钩子会漏（模型一轮可能调 3 个工具）；把每轮初始化写进工具拦截器会重入。</p>
 *
 * <p><b>纪律</b>：钩子永不抛异常；{@link #onTurnEnd()} 在异常路径也会被调用，
 * 所以实现必须容忍「没 start 就被 end」。示例用 ThreadLocal 存起始时间，两次回调各自判空。</p>
 */
@Slf4j
@Component
public class DemoTurnHook implements TurnHook {

    /** 本轮起始纳秒；只在「一轮」内有效，不跨轮传递 */
    private static final ThreadLocal<Long> TURN_START = new ThreadLocal<>();

    @Override
    public void onTurnStart(String userMessage) {
        TURN_START.set(System.nanoTime());
        // 真实项目里这里放「按本轮请求预取数据」，例如一次性查出该用户的基础档案塞进上下文，
        // 免得模型连续调三个工具各查一次
        log.info("DemoTurnHook#start#subject={} len={}",
                RunContext.subjectId(), userMessage == null ? 0 : userMessage.length());
    }

    @Override
    public void onTurnEnd() {
        Long start = TURN_START.get();
        TURN_START.remove();
        if (start == null) {
            // 异常路径或未 start 就 end：悄悄返回，不要 NPE
            return;
        }
        log.info("DemoTurnHook#end#subject={} cost={}ms",
                RunContext.subjectId(), (System.nanoTime() - start) / 1_000_000);
    }
}
