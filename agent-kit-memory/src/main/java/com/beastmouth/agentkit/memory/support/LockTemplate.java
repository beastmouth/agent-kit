package com.beastmouth.agentkit.memory.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson 分布式锁模板。
 *
 * <p>Agent 链路里需要加锁的地方只有两类：会话串行化（同一会话的并发消息不能交叉执行）、
 * 记忆提炼去重（同一用户在同一时刻只提炼一次）。两者都是「多副本部署时的互斥」，
 * 单机锁不够用。</p>
 *
 * <h3>释放锁必须判定持有者</h3>
 * <p>直接 {@code unlock()} 在「业务执行超过租期、锁已被别人拿走」时会抛
 * {@code IllegalMonitorStateException}，更糟的是某些实现会误删别人的锁。所以统一用
 * {@link RLock#isHeldByCurrentThread()} 判定后再释放。</p>
 *
 * <h3>降级而不是抛错</h3>
 * <p>Redis 抖动时 {@code tryLock} 抛异常，本模板捕获后按「没抢到」处理并执行 fallback。
 * 对缓存回源这类场景，锁只是优化；对会话串行化，上层会再判一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockTemplate {

    private final RedissonClient redissonClient;

    /**
     * 尝试加锁执行，拿不到锁时执行 fallback。
     *
     * @param lockKey     完整锁 key
     * @param waitMillis  等锁超时毫秒
     * @param leaseMillis 租期毫秒，{@code <= 0} 表示交给看门狗续期
     * @param action      临界区逻辑
     * @param fallback    没抢到锁时的兜底逻辑，可为 null（返回 null）
     */
    public <T> T tryExecute(String lockKey, long waitMillis, long leaseMillis,
                            Supplier<T> action, Supplier<T> fallback) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = waitMillis <= 0
                    ? lock.tryLock(0, leaseMillis, TimeUnit.MILLISECONDS)
                    : lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.debug("LockTemplate#tryExecute#未抢到锁 key={}", lockKey);
                return fallback == null ? null : fallback.get();
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LockTemplate#tryExecute#等锁被中断 key={}", lockKey);
            return fallback == null ? null : fallback.get();
        } catch (RuntimeException e) {
            log.warn("LockTemplate#tryExecute#加锁异常，按未抢到处理 key={} err={}", lockKey, e.toString());
            return fallback == null ? null : fallback.get();
        } finally {
            release(lock, lockKey);
        }
    }

    /**
     * 只执行一次的语义：抢到锁就执行 action 并返回 true，没抢到返回 false。
     *
     * <p>用于「同一用户同一时间只提炼一次记忆」这类去重，不是严格互斥。</p>
     */
    public boolean tryRunOnce(String lockKey, long waitMillis, long leaseMillis, Runnable action) {
        Boolean ran = tryExecute(lockKey, waitMillis, leaseMillis, () -> {
            action.run();
            return Boolean.TRUE;
        }, () -> Boolean.FALSE);
        return Boolean.TRUE.equals(ran);
    }

    private void release(RLock lock, String lockKey) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            // 释放失败不能影响业务：锁有租期兜底，最多多阻塞一个租期
            log.warn("LockTemplate#release#释放锁失败 key={} err={}", lockKey, e.toString());
        }
    }
}
