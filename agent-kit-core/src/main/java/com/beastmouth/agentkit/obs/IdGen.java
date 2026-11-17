package com.beastmouth.agentkit.obs;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量 ID 生成（埋点专用）。
 *
 * <p>单机内唯一、趋势递增，足够支撑观测量。用 41 位毫秒时间戳 + 10 位节点随机位 + 12 位序列，
 * 与雪花同构。</p>
 *
 * <p><b>为什么必须有"节点位"</b>：最初是 {@code (ts << 12) | seq}，看上去单机唯一就算够了。
 * 但后端是 2 个副本，两个 JVM 在同一毫秒各自从序列 0、1、2 数上去，<b>必然撞库</b>——
 * 观测表是批量 insert，一行主键冲突会让整批 50 条全灭，表现成"观测数据莫名其妙少了一半"。
 * 节点位取进程启动时的随机 10 位，两台机器撞的概率是 1/1024 再乘同一毫秒，实际可忽略；
 * 比读环境变量、配机器号都省事，也不会因为漏配而静默退化成单机模式。</p>
 *
 * <p>真要严格全局唯一，直接换成项目里的雪花实现（MyBatis-Plus {@code IdWorker}）即可，
 * 调用点只有 {@code next()} 一个。</p>
 *
 * <p><b>为什么不直接用「时间戳 + 序列」两段拼</b>：序列位只有 12 位，同一毫秒超过 4096 个
 * 就会回绕撞号。生产上碰不到（4096/ms = 400 万/秒），但单元测试在一个紧循环里生成
 * 5000 个 id，稳稳落进同一毫秒 —— 于是这个"理论上不会发生"的问题在 CI 里随机复现，
 * 表现为「有时绿有时红」，很难查。改用单调自增（{@link AtomicLong}）后，
 * 同毫秒内直接覆盖低位递增，彻底不会重号，也不再依赖 `System.currentTimeMillis()`
 * 的分辨率。</p>
 */
public final class IdGen {

    /**
     * 上一次发出的 id。用 CAS 保证：
     * <ul>
     *   <li>严格单增（同毫秒内低位递增，跨毫秒时被新的时间戳推高）；</li>
     *   <li>进程内绝对不重号（CAS 成功的那一个才拿到这个值）；</li>
     *   <li>趋势递增，做主键/排序都不会造成页分裂。</li>
     * </ul>
     */
    private static final AtomicLong LAST = new AtomicLong();

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 进程级节点位：启动时随机一次，同一次运行内不变（保证同一 JVM 内不撞） */
    private static final long NODE = RANDOM.nextInt(1 << 10);

    private IdGen() {
    }

    /** 41 位时间戳 | 10 位节点 | 12 位序列（同毫秒超量时低位继续自增） */
    public static long next() {
        return LAST.updateAndGet(prev -> {
            long base = (System.currentTimeMillis() << 22) | (NODE << 12);
            // 时钟回拨时 base 会变小，取 prev+1 保证不倒退、不重号
            return Math.max(base, prev + 1);
        });
    }

    /** W3C Trace Context 的 32 位十六进制 traceId（没接 OTel 时的兜底） */
    public static String traceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
