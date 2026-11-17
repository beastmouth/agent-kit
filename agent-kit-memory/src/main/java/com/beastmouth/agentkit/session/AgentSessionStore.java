package com.beastmouth.agentkit.session;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.session.mapper.AgentMessageMapper;
import com.beastmouth.agentkit.session.mapper.AgentSessionMapper;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 会话存取。
 *
 * <p><b>所有方法都要显式传 {@code namespace} 与 {@code owner}</b>，不读线程上下文。
 * 理由：会话是最容易被别处复用的能力（后台任务、批处理、多端同步都要读写会话），
 * 一旦它依赖 ThreadLocal，任何一次「忘了先设上下文」都会退化成查别人数据——
 * 而这类 bug 不报错，只是数据错了。显式参数把这种风险变成编译错误。</p>
 *
 * <p>归属校验在这里收口：别人的会话一律按「不存在」处理，不区分「没有」与「无权访问」，
 * 避免通过 ID 探测他人会话。</p>
 *
 * <p>渠道隔离也在这里：网页与 IM 各走各的会话（{@code channel}），
 * 网页列表额外做一次「来源是否仍然有效」的过滤，见 {@link #listVisible}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionStore {

    private static final int TITLE_MAX = 24;
    /** 手动改名放得比自动标题宽：自动标题是截取，手动改名是调用方自己起的名字 */
    private static final int TITLE_MANUAL_MAX = 40;
    private static final String DEFAULT_TITLE = "新的对话";

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final AiProperties properties;

    /**
     * 渠道来源登记表，可能不存在（没接 IM 模块）。用 {@link ObjectProvider} 而不是
     * {@code @Autowired(required = false)}，是为了在没有实现时也能正常启动。
     */
    private final ObjectProvider<SessionSourceRegistry> sourceRegistries;

    /**
     * L1 上下文包缓存。同样用 {@link ObjectProvider}：单测与精简部署没有 Redisson 时
     * 退化为「无缓存」，行为与不接缓存时完全一致。
     */
    private final ObjectProvider<AgentContextCache> contextCaches;

    /** 某归属方的会话列表，按最近活跃倒序 */
    public List<AgentSession> list(String namespace, String owner) {
        requireScope(namespace, owner);
        return sessionMapper.selectList(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getNamespace, namespace)
                .eq(AgentSession::getOwner, owner)
                .orderByDesc(AgentSession::getLastMessageAt)
                .orderByDesc(AgentSession::getId));
    }

    /**
     * 网页端可见的会话：本人的会话 + 本人<b>当前仍绑定</b>的渠道号产生的会话。
     *
     * <p>渠道会话本来就记在绑定的 {@code owner} 名下，正常情况下天然只有本人可见；
     * 多这一层过滤是为了处理解绑/换绑：解绑后老会话不该继续出现在网页列表里，
     * 但也不删——重新绑回同一个账号历史自动恢复。</p>
     *
     * <p>没有渠道模块时（{@link SessionSourceRegistry} 不存在）退化为按归属方过滤。</p>
     */
    public List<AgentSession> listVisible(String namespace, String owner) {
        List<AgentSession> sessions = list(namespace, owner);
        boolean hasChannelSession = sessions.stream().anyMatch(item -> !item.isWritable());
        if (!hasChannelSession) {
            return sessions;
        }
        SessionSourceRegistry registry = sourceRegistries.getIfAvailable();
        if (registry == null) {
            return sessions;
        }
        Set<String> sources = registry.activeSourceIds(namespace, owner);
        return sessions.stream()
                .filter(item -> item.isWritable()
                        || (StringUtils.hasText(item.getSourceId()) && sources.contains(item.getSourceId())))
                .toList();
    }

    /** 新建网页会话 */
    @Transactional(rollbackFor = Exception.class)
    public AgentSession create(String namespace, String owner, String title) {
        return create(namespace, owner, title, AgentSession.CHANNEL_WEB, null);
    }

    /**
     * 新建会话。
     *
     * @param channel  来源渠道，{@code null} 按网页处理
     * @param sourceId 渠道内来源标识（如 IM 的 open_id），网页传 {@code null}
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentSession create(String namespace, String owner, String title, String channel, String sourceId) {
        requireScope(namespace, owner);
        Long count = sessionMapper.selectCount(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getNamespace, namespace)
                .eq(AgentSession::getOwner, owner));
        if (count != null && count >= properties.getMaxSessionsPerOwner()) {
            throw AgentKitException.of(ErrorCode.SESSION_LIMIT);
        }
        AgentSession session = new AgentSession();
        session.setNamespace(namespace);
        session.setOwner(owner);
        session.setTitle(title == null || title.isBlank() ? DEFAULT_TITLE : truncate(title));
        session.setChannel(StringUtils.hasText(channel) ? channel : AgentSession.CHANNEL_WEB);
        session.setSourceId(sourceId);
        session.setMessageCount(0);
        session.setLastMessageAt(LocalDateTime.now());
        sessionMapper.insert(session);
        log.info("AgentSessionStore#create#新建会话 namespace={} owner={} sessionId={} channel={} sourceId={}",
                namespace, owner, session.getId(), session.getChannel(), sourceId);
        return session;
    }

    /**
     * 渠道内最近一次活跃的会话。
     *
     * <p>IM 用户的「当前会话」不能只靠 Redis 指针：缓存过期、重启、换 Pod 都会让指针消失，
     * 而用户期望的是「一直在同一个会话里聊」。指针丢了就回落到这里取最近一条，兜住连续性。</p>
     */
    public AgentSession latestBySource(String namespace, String owner, String channel, String sourceId) {
        if (!StringUtils.hasText(sourceId)) {
            return null;
        }
        requireScope(namespace, owner);
        return sessionMapper.selectOne(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getNamespace, namespace)
                .eq(AgentSession::getOwner, owner)
                .eq(AgentSession::getChannel, channel)
                .eq(AgentSession::getSourceId, sourceId)
                .orderByDesc(AgentSession::getId)
                .last("LIMIT 1"));
    }

    /** 取会话并校验归属 */
    public AgentSession getRequired(Long sessionId, String namespace, String owner) {
        AgentSession session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null || !sameScope(session, namespace, owner)) {
            throw AgentKitException.of(ErrorCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    /**
     * 取「可写入」的会话：归属 + 渠道双校验。
     *
     * <p>网页端只能往网页会话里写。IM 会话在网页上是只读的：两端各有一套上下文，
     * 在网页里插一句会把 IM 那边的对话节奏打断，用户回到 IM 会觉得机器人「失忆了一轮」。</p>
     */
    public AgentSession getWritable(Long sessionId, String namespace, String owner) {
        AgentSession session = getRequired(sessionId, namespace, owner);
        if (!session.isWritable()) {
            log.info("AgentSessionStore#getWritable#拒绝写入只读会话 owner={} sessionId={} channel={}",
                    owner, sessionId, session.getChannel());
            throw AgentKitException.of(ErrorCode.SESSION_READONLY);
        }
        return session;
    }

    /**
     * 重命名会话。
     *
     * <p>只改标题这一个标签，不动消息、不动渠道：网页端把 IM 会话当只读指的是「不能接着聊」，
     * 给它起个自己能认出来的名字并不影响 IM 那边的上下文。</p>
     *
     * <p>标题为空直接拒绝：空标题在前端会退化成占位文案，用户改完看不出改过，
     * 下次再进来还以为没保存成功。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentSession rename(Long sessionId, String namespace, String owner, String title) {
        AgentSession session = getRequired(sessionId, namespace, owner);
        String name = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            throw AgentKitException.of(ErrorCode.SESSION_TITLE_INVALID);
        }
        String finalTitle = name.length() <= TITLE_MANUAL_MAX ? name : name.substring(0, TITLE_MANUAL_MAX);
        // 只带要改的字段走 updateById：null 字段会被 MyBatis-Plus 跳过，
        // 不会把 message_count / last_message_at 这些并发写的字段覆盖回旧值
        AgentSession patch = new AgentSession();
        patch.setId(sessionId);
        patch.setTitle(finalTitle);
        patch.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(patch);
        session.setTitle(finalTitle);
        log.info("AgentSessionStore#rename#会话改名 owner={} sessionId={} title={}",
                owner, sessionId, finalTitle);
        return session;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long sessionId, String namespace, String owner) {
        AgentSession session = getRequired(sessionId, namespace, owner);
        sessionMapper.deleteById(sessionId);
        messageMapper.delete(Wrappers.<AgentMessage>lambdaQuery().eq(AgentMessage::getSessionId, sessionId));
        // 会话没了包必须跟着没：不 evict 的话，重建同 ID 会读到旧包
        AgentContextCache cache = usableCache();
        if (cache != null) {
            cache.evict(session.getNamespace(), session.getOwner(), sessionId);
        }
        log.info("AgentSessionStore#delete#删除会话 owner={} sessionId={}", owner, sessionId);
    }

    // ---------------- 上下文包缓存辅助 ----------------

    /** 可用的上下文缓存：模块缺失或开关关闭时返回 null，调用方直接走纯 MySQL 路径 */
    private AgentContextCache usableCache() {
        AgentContextCache cache = contextCaches.getIfAvailable();
        return cache != null && cache.enabled() ? cache : null;
    }

    /** 按会话加载最近窗口（时间正序），供 recentHistory 与回填共用 */
    private List<AgentMessage> loadLatestMessages(Long sessionId) {
        List<AgentMessage> latest = new java.util.ArrayList<>(messageMapper.selectList(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getSessionId, sessionId)
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT " + Math.max(2, properties.getHistoryLimit()))));
        java.util.Collections.reverse(latest);
        return latest;
    }

    /** 最近一条非 main 的业务子 Agent 名（纯查询，归属校验由调用方负责） */
    private String queryLastDelegatedAgent(Long sessionId) {
        AgentMessage latest = messageMapper.selectOne(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getSessionId, sessionId)
                .eq(AgentMessage::getRole, "ASSISTANT")
                .isNotNull(AgentMessage::getAgentName)
                .ne(AgentMessage::getAgentName, "main")
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getAgentName();
    }

    /** 助手回执的工具来源注记，拼在给模型看的历史里（不落库、不进前端） */
    static final String TOOL_EVIDENCE_MARK = "\n（系统注：这条回复基于本轮真实执行的工具结果）";

    /**
     * DB 消息 → 模型消息的角色映射，读路径与写穿共用同一份口径。
     *
     * <p>助手消息带工具执行记录（{@code tool_events} 非空）时，在内容末尾补一句来源注记。
     * <b>为什么要有这句</b>：历史里多组「用户提要求 → 助手『已完成』」的样例原本只有最终文本，
     * 模型上下文模仿后学会了跳过工具直接空口回执。注记让模型看到「这类回复背后是有工具的」，
     * 配合 system prompt 的回执真实性规则切断模仿源。</p>
     */
    private List<ChatMessage> toChatMessages(List<AgentMessage> messages) {
        return messages.stream()
                .map(AgentSessionStore::toChatMessage)
                .toList();
    }

    /** 单条转换；静态包私有便于单测覆盖注记逻辑 */
    static ChatMessage toChatMessage(AgentMessage item) {
        String role = "USER".equals(item.getRole()) ? "user" : "assistant";
        String content = item.getContent();
        if ("assistant".equals(role) && hasToolEvidence(item.getToolEvents())) {
            content = content + TOOL_EVIDENCE_MARK;
        }
        return new ChatMessage(role, content);
    }

    /** tool_events 非空且不是空数组即视为有工具证据 */
    static boolean hasToolEvidence(String toolEvents) {
        if (toolEvents == null) {
            return false;
        }
        String trimmed = toolEvents.trim();
        return !trimmed.isEmpty() && !"[]".equals(trimmed);
    }

    /** 会话全部消息，按时间正序 */
    public List<AgentMessage> messages(Long sessionId, String namespace, String owner) {
        getRequired(sessionId, namespace, owner);
        return messageMapper.selectList(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getSessionId, sessionId)
                .orderByAsc(AgentMessage::getId));
    }

    /**
     * 最近若干轮对话，用于拼上下文。
     *
     * <p>读路径三级：L1 上下文包命中即短路（连归属校验的 {@code selectById} 都省掉，
     * key 里带 namespace+owner 保证他人探测必然 miss 落回校验）→ miss 回源 MySQL → 回填 Redis。
     * 归属校验失败永不走缓存，安全边界仍在 MySQL 层。</p>
     */
    public List<ChatMessage> recentHistory(Long sessionId, String namespace, String owner) {
        AgentContextCache cache = usableCache();
        if (cache != null) {
            AgentContextCache.ContextPack pack = cache.get(namespace, owner, sessionId);
            if (pack != null) {
                return pack.getHistory();
            }
        }
        AgentSession session = getRequired(sessionId, namespace, owner);
        List<AgentMessage> latest = loadLatestMessages(session.getId());
        if (cache != null) {
            // 回填时把 lastAgent 一并带上：调用顺序是 recentHistory → append →
            // lastDelegatedAgent，回填后 lastDelegatedAgent 直接命中包，不再单独查库
            AgentMessage lastMsg = latest.isEmpty() ? null : latest.get(latest.size() - 1);
            long lastId = lastMsg == null ? 0L : lastMsg.getId();
            cache.put(namespace, owner, sessionId,
                    AgentContextCache.ContextPack.of(lastId, queryLastDelegatedAgent(sessionId), toChatMessages(latest)));
        }
        return toChatMessages(latest);
    }

    /**
     * 最近一次真正处理业务的子 Agent 名（路由直答的 {@code main} 不算）。
     *
     * <p>给主 Agent 的「短句追问延续」兜底用：「那前天呢」这类省略句没有关键词，
     * 路由模型也可能失手，此时延续上一轮的领域比回一句无关话术有用得多。</p>
     */
    public String lastDelegatedAgent(Long sessionId, String namespace, String owner) {
        AgentContextCache cache = usableCache();
        if (cache != null) {
            AgentContextCache.ContextPack pack = cache.get(namespace, owner, sessionId);
            if (pack != null) {
                return pack.getLastAgent();
            }
        }
        getRequired(sessionId, namespace, owner);
        // 不在这里回填：包的 history 由 recentHistory 统一构建，此处单独 put 会造出缺历史的残包
        return queryLastDelegatedAgent(sessionId);
    }

    /**
     * 追加一条消息，并同步会话的计数、活跃时间与标题。
     *
     * <p><b>写穿</b>：包存在且其 lastMessageId 与追加前库中最新 ID 一致时，直接在包上追加
     * 并回写 Redis——下一轮的读路径命中，连一次 MySQL 都不碰。包落后（乐观校验失败）
     * 或包不存在（此前 miss）时分别 evict / 跳过，绝不用内存里的局部视图覆盖可能更新
     * 的包，脏上下文比多一次回源严重得多。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentMessage append(AgentSession session, String role, String agentName, String content, String toolEvents) {
        // 追加前的库中最新消息 ID：乐观校验的基准。一次主键范围的 select，写路径多这一下不亏
        Long prevLastId = null;
        AgentContextCache cache = usableCache();
        if (cache != null) {
            AgentMessage latest = messageMapper.selectOne(Wrappers.<AgentMessage>lambdaQuery()
                    .eq(AgentMessage::getSessionId, session.getId())
                    .orderByDesc(AgentMessage::getId)
                    .last("LIMIT 1"));
            prevLastId = latest == null ? 0L : latest.getId();
        }

        // 分区与归属继承会话而不是取当前上下文：消息必须与所在会话同分区，
        // 否则按会话查消息时会因为条件不匹配而「少一条」
        AgentMessage message = new AgentMessage();
        message.setSessionId(session.getId());
        message.setNamespace(session.getNamespace());
        message.setOwner(session.getOwner());
        message.setRole(role);
        message.setAgentName(agentName);
        message.setContent(content);
        message.setToolEvents(toolEvents);
        messageMapper.insert(message);

        LocalDateTime now = LocalDateTime.now();
        var wrapper = Wrappers.<AgentSession>lambdaUpdate()
                .eq(AgentSession::getId, session.getId())
                .set(AgentSession::getLastMessageAt, now)
                .set(AgentSession::getUpdateTime, now)
                // 计数必须走 SQL 自增：一轮对话里 append 两次，用内存值会都写成 1
                .setSql("message_count = message_count + 1");
        if (agentName != null) {
            wrapper.set(AgentSession::getAgentName, agentName);
        }
        // 标题跟随首条用户消息。
        // 判据用「这是不是会话里的第一条消息」，而不是比较标题字符串：
        // 前端新建会话时会带一个本地化的占位标题（「新会话」/「New chat」），
        // 比字符串的话英文用户永远改不了标题，而且占位值散落在前后端两处。
        if ("USER".equals(role) && (session.getMessageCount() == null || session.getMessageCount() == 0)) {
            wrapper.set(AgentSession::getTitle, truncate(content));
        }
        sessionMapper.update(null, wrapper);

        // 写穿：放在事务内最后，包与库同轮更新；校验失败宁可丢包（下轮回源）不写脏数据
        if (cache != null && prevLastId != null) {
            AgentContextCache.ContextPack pack = cache.get(session.getNamespace(), session.getOwner(), session.getId());
            if (pack != null) {
                if (pack.getLastMessageId() == prevLastId) {
                    pack.setLastMessageId(message.getId());
                    pack.getHistory().add(new ChatMessage("USER".equals(role) ? "user" : "assistant", content));
                    int limit = Math.max(2, properties.getHistoryLimit());
                    while (pack.getHistory().size() > limit) {
                        pack.getHistory().remove(0);
                    }
                    if (agentName != null && !"main".equals(agentName)) {
                        pack.setLastAgent(agentName);
                    }
                    cache.put(session.getNamespace(), session.getOwner(), session.getId(), pack);
                } else {
                    log.info("AgentSessionStore#append#上下文包落后，evict 回源 sessionId={}", session.getId());
                    cache.evict(session.getNamespace(), session.getOwner(), session.getId());
                }
            }
        }
        return message;
    }

    private String truncate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= TITLE_MAX ? flat : flat.substring(0, TITLE_MAX) + "…";
    }

    private void requireScope(String namespace, String owner) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(owner)) {
            throw AgentKitException.of(ErrorCode.INVALID_CONFIG,
                    "会话必须带 namespace 与 owner：namespace 用于分区，owner 用于归属校验");
        }
    }

    private boolean sameScope(AgentSession session, String namespace, String owner) {
        return Objects.equals(session.getNamespace(), namespace) && Objects.equals(session.getOwner(), owner);
    }
}
