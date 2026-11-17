package com.beastmouth.agentkit.starter;

import com.beastmouth.agentkit.memory.support.LockTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁模板的异常边界：<b>临界区的异常必须原样上抛</b>。
 *
 * <p>这是一条踩过的坑，不是理论洁癖。早期实现把 {@code action.get()} 也包在
 * {@code catch (RuntimeException)} 里，于是模型调用失败被当成「没抢到锁」，
 * 用户看到「该会话正在处理上一条消息」，真正原因连日志都没有——排查方向直接跑偏。</p>
 */
class LockTemplateTest {

    @Test
    @DisplayName("临界区抛业务异常时原样上抛，不被当成锁冲突吞掉")
    void businessExceptionPropagates() {
        LockTemplate template = template(lockAcquired(true));
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                template.tryRunOnce("k", 0, -1, () -> {
                    throw new IllegalStateException("模型渠道挂了");
                }));
        assertEquals("模型渠道挂了", e.getMessage());
    }

    @Test
    @DisplayName("抢不到锁返回 false，临界区一次都不执行")
    void lockNotAcquired() {
        LockTemplate template = template(lockAcquired(false));
        AtomicBoolean ran = new AtomicBoolean(false);
        assertFalse(template.tryRunOnce("k", 0, -1, () -> ran.set(true)));
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("加锁阶段 Redis 抖动时降级：按没抢到处理，不抛也不执行")
    void redisFailureDegrades() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                    .thenThrow(new IllegalStateException("redis down"));
        } catch (InterruptedException ignored) {
            throw new IllegalStateException(ignored);
        }
        AtomicBoolean ran = new AtomicBoolean(false);
        assertFalse(new LockTemplate(client).tryRunOnce("k", 0, -1, () -> ran.set(true)));
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("正常路径：抢到锁执行、返回 true，并释放锁")
    void happyPath() {
        RLock lock = lockAcquired(true);
        AtomicBoolean ran = new AtomicBoolean(false);
        assertTrue(template(lock).tryRunOnce("k", 0, -1, () -> ran.set(true)));
        assertTrue(ran.get());
        try {
            org.mockito.Mockito.verify(lock).unlock();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static LockTemplate template(RLock lock) {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getLock(anyString())).thenReturn(lock);
        return new LockTemplate(client);
    }

    private static RLock lockAcquired(boolean acquired) {
        RLock lock = mock(RLock.class);
        try {
            when(lock.tryLock(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                    .thenReturn(acquired);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(acquired);
        return lock;
    }
}
