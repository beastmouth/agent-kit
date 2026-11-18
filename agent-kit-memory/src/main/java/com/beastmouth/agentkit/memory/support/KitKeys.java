package com.beastmouth.agentkit.memory.support;

/**
 * 锁与缓存 key 的统一拼装。
 *
 * <p>所有 key 都带 {@code agent-kit:} 前缀：与使用方自己的业务 key 隔开，
 * 出问题时能一眼看出「这些是脚手架写的」，也便于按前缀批量清理。</p>
 *
 * <p>分区键 {@code namespace} 必须进 key——漏了它，两个分区的会话上下文会互相覆盖。</p>
 */
public final class KitKeys {

    /** 全局前缀，改这里等于换命名空间 */
    public static final String PREFIX = "agent-kit:";

    private KitKeys() {
    }

    /** 会话上下文包缓存（L1） */
    public static String agentContext(String namespace, String owner, long sessionId) {
        return PREFIX + "ctx:" + part(namespace) + ":" + part(owner) + ":" + sessionId;
    }

    /**
     * 上下文摘要（压缩产物），按前缀指纹分版本。
     *
     * <p>{@code coveredKey} 是被压缩前缀的内容指纹：同一指纹必然命中同一份摘要，
     * 这是前缀缓存能继续生效的前提。{@code scope} 是隔离键（通常为调用方标识），
     * 只负责把不同租户分开，不参与「摘要是否可复用」的判定。</p>
     */
    public static String contextSummary(String scope, String coveredKey) {
        return PREFIX + "ctxsum:" + part(scope) + ":" + part(coveredKey);
    }

    /** 记忆版本号：记忆一写入就 bump，让检索缓存自然失效 */
    public static String memoryVersion(String namespace, String owner) {
        return PREFIX + "memver:" + part(namespace) + ":" + part(owner);
    }

    /** 会话级互斥锁 */
    public static String sessionLock(Object sessionId) {
        return PREFIX + "lock:session:" + sessionId;
    }

    /** 记忆提炼去重锁 */
    public static String memoryLock(String namespace, String owner) {
        return PREFIX + "lock:memory:" + part(namespace) + ":" + part(owner);
    }

    /** key 里不能出现分隔符冒号带来的歧义；null/空统一写成下划线占位 */
    private static String part(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        return value.replace(':', '_');
    }
}
