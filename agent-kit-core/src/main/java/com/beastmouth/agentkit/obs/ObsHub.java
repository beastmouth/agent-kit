package com.beastmouth.agentkit.obs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 静态枢纽：把 Spring 容器里的观测组件暴露给纯静态的 {@link RunScope}。
 *
 * <h3>为什么需要它</h3>
 * <p>{@code RunScope} 是静态工具类（业务里到处 {@code RunScope.current()}，不能要求每个调用点
 * 都注入一个 Bean）。但 P1 的指标、P2 的 tracing 都是 Spring 管理的组件——
 * 静态类拿不到依赖注入的结果。</p>
 *
 * <p>解法：在容器启动时把组件塞进一个静态引用（构造器注入 → 赋值静态字段），
 * 静态代码从 {@link #metrics()} / {@link #tracing()} 取。<b>没启动容器（单测、CLI 裸跑）时
 * 返回 noop 实现</b>，所以 {@code new RunScope()} 的老测试一行都不用改。</p>
 *
 * <p>这个类刻意只做「转发」，不放任何逻辑：一旦它开始执行业务判断，
 * 静态状态就会变成第二个容器，谁也说不清初始化顺序。</p>
 */
@Slf4j
@Component
public class ObsHub {

    private static volatile AiMetrics metrics = AiMetrics.noop();
    private static volatile AiTracing tracing = AiTracing.noop();

    public ObsHub(AiMetrics aiMetrics, AiTracing aiTracing) {
        ObsHub.metrics = aiMetrics;
        ObsHub.tracing = aiTracing;
        log.info("ObsHub#init#观测枢纽就绪 metrics={} tracing={}",
                aiMetrics.getClass().getSimpleName(), aiTracing.describe());
    }

    public static AiMetrics metrics() {
        return metrics;
    }

    public static AiTracing tracing() {
        return tracing;
    }

    /** 仅供测试：模拟容器未启动 / 已关闭 */
    static void reset() {
        metrics = AiMetrics.noop();
        tracing = AiTracing.noop();
    }
}
