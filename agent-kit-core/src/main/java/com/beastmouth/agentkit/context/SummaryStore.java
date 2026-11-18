package com.beastmouth.agentkit.context;

/**
 * 摘要存储。
 *
 * <p>存的是「某个前缀指纹对应的摘要正文」。抽象成接口的唯一理由是：core 不能引 Redisson，
 * 而多副本部署又必须把摘要放在共享存储里。core 提供内存实现，{@code agent-kit-memory}
 * 提供 Redis 实现，用配置二选一。</p>
 *
 * <h3>key 为什么是「隔离键 + 前缀指纹」</h3>
 * <p>指纹是内容维度的身份：前缀内容一致，摘要就应该一致——这正是前缀缓存能继续生效的前提。
 * 隔离键（通常取调用方标识）负责把不同租户/用户的缓存分开，避免「合规上不该共享的东西
 * 在缓存层被共享」，即使共享本身在语义上无害。</p>
 *
 * <h3>任何实现都不许抛异常</h3>
 * <p>存储挂了就当没命中，回源重新生成一份摘要即可。摘要不是正确性依赖。</p>
 */
public interface SummaryStore {

    /** 实现标识，用于启动日志 */
    default String id() {
        return getClass().getSimpleName();
    }

    /**
     * 读摘要。
     *
     * @param scope      隔离键，null 按空串处理
     * @param coveredKey 前缀指纹
     * @return 命中返回摘要正文；未命中 / 发生任何异常一律返回 {@code null}
     */
    String get(String scope, String coveredKey);

    /** 写摘要。失败只应打日志，不许抛给调用方 */
    void put(String scope, String coveredKey, String summary);
}
