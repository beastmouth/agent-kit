package com.beastmouth.agentkit.session;

import com.beastmouth.agentkit.memory.support.KitKeys;
import com.beastmouth.agentkit.memory.support.LockTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话级串行执行：同一会话同时只允许一轮对话在跑。
 *
 * <p>单机部署时进程内串行化就够，多副本后就不成立了。这里用一把 Redis 锁把入口统一收口：</p>
 * <ul>
 *   <li><b>跨副本</b>：同一会话的消息落到哪个 Pod 都抢同一把锁；</li>
 *   <li><b>防双击/重试</b>：前端连点两下「发送」，第二条直接被拦下，不会写两条用户消息；</li>
 *   <li><b>防重复执行</b>：两轮并发跑同一句话，工具会被调用两次——这是唯一会写脏数据的场景。</li>
 * </ul>
 *
 * <p>租期交给看门狗（{@code leaseMillis = -1}）：一轮对话可能跑几分钟，定长租期会在中途
 * 自动解锁，等于没锁。Pod 被强杀时连接断开，看门狗随进程消失，锁自然释放，不会永久卡死。</p>
 *
 * <p>锁是<b>可用性 vs 正确性</b>的取舍点：拿不到锁时前端提示「上一条还在处理」。
 * 宁可让用户重发一次，也不能让同一句话被执行两遍。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionLock {

    /** 网页端：不等，撞上了立刻回话。SSE 连接上空等几秒比直接提示更让人困惑 */
    public static final long WEB_WAIT_MILLIS = 0L;

    /**
     * IM 端：稍等一下。IM 用户连发两三条是常态，等 5 秒通常就能接上，
     * 比回一句「上一条还在处理」更自然。
     */
    public static final long IM_WAIT_MILLIS = 5_000L;

    private final LockTemplate lockTemplate;

    /**
     * 在会话锁内执行。
     *
     * @param sessionId  会话 ID
     * @param waitMillis 等锁时长，0 表示不等待
     * @param action     临界区：读历史、写消息、调模型、落库
     * @return {@code true} 已执行；{@code false} 锁被别的请求占着，调用方负责提示
     */
    public boolean tryRun(Long sessionId, long waitMillis, Runnable action) {
        boolean ran = lockTemplate.tryRunOnce(KitKeys.sessionLock(sessionId), waitMillis, -1L, action);
        if (!ran) {
            log.info("AgentSessionLock#tryRun#会话正被处理 sessionId={} wait={}ms", sessionId, waitMillis);
        }
        return ran;
    }
}
