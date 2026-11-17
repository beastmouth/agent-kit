package com.beastmouth.agentkit.session;

import java.util.Set;

/**
 * 渠道会话来源登记表（扩展点）。
 *
 * <p>网页端要回答一个问题：「这个人的 IM 会话，哪些现在还算数？」。答案在 IM 模块手里
 * （绑定关系表），而 agent 脚手架不能反向依赖 IM 模块，于是这里留一个只有方法的接口，
 * 由渠道模块实现、Spring 注入；没有渠道模块时（比如只跑网页）直接拿不到实现，
 * 列表就只按归属方过滤。</p>
 *
 * <p>为什么按「来源 ID」而不是「会话 ID」过滤：绑定关系可能被解绑再绑到别人身上，
 * 会话本身不会跟着搬家。用来源 ID 过滤，解绑后历史自动隐藏、重新绑回同一个账号
 * 历史又自动回来，不必删数据。</p>
 */
public interface SessionSourceRegistry {

    /**
     * 该归属方当前仍然有效的渠道来源标识。
     *
     * @param namespace 分区键
     * @param owner     归属方
     * @return 例如当前绑定的 IM {@code open_id}；没有则空集合，不返回 {@code null}
     */
    Set<String> activeSourceIds(String namespace, String owner);
}
