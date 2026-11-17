package com.beastmouth.agentkit.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.memory.mapper.AgentMemoryMapper;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.provider.ModelProvider;
import com.beastmouth.agentkit.provider.ModelProviderRegistry;
import com.beastmouth.agentkit.memory.support.KitKeys;
import com.beastmouth.agentkit.memory.support.LockTemplate;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 长期记忆管家：提炼（写）、检索（读）、自愈重建。
 *
 * <p>借鉴 MemOS 的「提炼 → 冲突沉淀 → 检索注入」三层，做成一个与业务无关的实现：</p>
 * <ul>
 *   <li><b>提炼</b>：每轮对话结束后异步交给模型判断 ADD / UPDATE / DROP；
 *       只记跨会话有用的稳定信息，可从数据源直查的明细不记——查数据比记记忆可靠。</li>
 *   <li><b>冲突沉淀</b>：UPDATE/DROP 时旧记忆置 {@code SUPERSEDED}（先写后删，保留痕迹），
 *       同时把旧向量从 Milvus 删掉。</li>
 *   <li><b>检索注入</b>：每轮对话开始，把输入向量化，在<b>该分区 + 该归属方</b>的记忆里
 *       按相似度取 top-K，注入子 Agent 的系统提示词。</li>
 * </ul>
 *
 * <p><b>两个作用域参数都是不透明字符串</b>：{@code namespace} 是分区（租户/组织/项目），
 * {@code owner} 是归属方（用户/工单/设备）；{@code owner} 传 {@code null} 表示只看
 * 分区内的共享记忆。脚手架不解释这两者的含义，也不查任何用户表。</p>
 *
 * <p>可用性原则：记忆是锦上添花，<b>任何故障都不能影响正常对话</b>——
 * 检索失败降级为空列表，提炼失败留日志等下一轮，Milvus 整个丢掉还能按 MySQL 表重建。</p>
 */
@Slf4j
@Service
public class AgentMemoryService {

    private final AgentMemoryMapper memoryMapper;
    private final MemoryProperties properties;
    private final AiProperties aiProperties;
    private final EmbeddingClient embeddingClient;
    private final MilvusVectorStore vectorStore;
    private final ModelProviderRegistry providerRegistry;
    private final LockTemplate lockTemplate;

    /** 单线程足够：提炼按轮次串行即可，多线程反而可能乱序写 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-memory");
        thread.setDaemon(true);
        return thread;
    });

    public AgentMemoryService(AgentMemoryMapper memoryMapper, MemoryProperties properties,
                              AiProperties aiProperties, EmbeddingClient embeddingClient,
                              MilvusVectorStore vectorStore, ModelProviderRegistry providerRegistry,
                              LockTemplate lockTemplate) {
        this.memoryMapper = memoryMapper;
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.providerRegistry = providerRegistry;
        this.lockTemplate = lockTemplate;
    }

    // ---------------- 检索（读路径，同步） ----------------

    /**
     * 检索与该输入相关的长期记忆（该归属方的个人记忆 + 分区共享记忆，按相似度过滤）。
     * 返回带类型前缀的行文本，直接进系统提示词；任何失败返回空列表。
     *
     * <p>分槽注入：规则（RULE，含共享规则）最多占 {@code ruleTopK} 个独立槽位且
     * 排在前面，其余类型共享剩下的名额——防止规则随使用增长后把普通记忆全部挤出检索
     * 结果。规则不足时名额让渡，总条数仍 ≤ topK。Milvus 索引不存 kind，这里用
     * 「多取几倍候选、按 MySQL 的 kind 分槽」实现，避免动 collection 结构。</p>
     *
     * @param namespace 分区键，必填
     * @param owner     归属方；{@code null} 表示只检索分区内的共享记忆
     */
    public List<String> recall(String namespace, String owner, String message) {
        if (!properties.isEnabled() || !StringUtils.hasText(namespace) || !StringUtils.hasText(message)) {
            return List.of();
        }
        try {
            float[] vector = embeddingClient.embed(message);
            // 嵌入先做：嵌入服务与向量库是两条独立依赖，嵌入不可用时直接降级，
            // 不必再花 10 秒去连 Milvus。结构校验与重建必须在检索之前——
            // 结构重建后索引是空的，不立刻按 MySQL 灌回，检索会一直返回空，
            // 表现为「记忆全丢了」但没有任何报错
            vectorStore.ensureReady();
            if (vectorStore.takeLegacyResetSignal()) {
                rebuildFromMysql();
            }
            // 候选池放大：规则槽 + 普通槽都要从同一份相似度排序里挑，取 3 倍保底
            int candidates = Math.min(properties.getTopK() * 3, 30);
            // 个人记忆（owner==本人）与共享记忆（owner 为空）各搜一遍再按相似度归并。
            // 两次检索都带分区过滤：两个分区都有共享记忆行，只按 owner 过滤会串分区
            List<MilvusVectorStore.Hit> mine = StringUtils.hasText(owner)
                    ? vectorStore.search(namespace, owner, vector, candidates) : List.of();
            List<MilvusVectorStore.Hit> shared = vectorStore.search(namespace, null, vector, candidates);
            List<MilvusVectorStore.Hit> hits = new ArrayList<>(mine.size() + shared.size());
            hits.addAll(mine);
            hits.addAll(shared);
            hits.sort((a, b) -> Float.compare(b.score(), a.score()));
            List<Long> ids = hits.stream()
                    .filter(hit -> hit.score() >= properties.getMinScore())
                    .map(MilvusVectorStore.Hit::id)
                    .toList();
            if (ids.isEmpty()) {
                return List.of();
            }
            Map<Long, AgentMemory> byId = new LinkedHashMap<>();
            memoryMapper.selectList(Wrappers.<AgentMemory>lambdaQuery()
                            .eq(AgentMemory::getNamespace, namespace)
                            .in(AgentMemory::getId, ids)).stream()
                    // 精过滤：本人的记忆，或分区内共享（Milvus 侧只是粗筛）
                    .filter(item -> (StringUtils.hasText(owner) && owner.equals(item.getOwner()))
                            || item.getOwner() == null)
                    .filter(item -> AgentMemory.STATUS_ACTIVE.equals(item.getStatus()))
                    .forEach(item -> byId.put(item.getId(), item));
            // 按相似度顺序分槽
            List<AgentMemory> rules = new ArrayList<>();
            List<AgentMemory> others = new ArrayList<>();
            for (Long id : ids) {
                AgentMemory memory = byId.get(id);
                if (memory == null) {
                    continue;
                }
                if (AgentMemory.KIND_RULE.equals(memory.getKind())) {
                    rules.add(memory);
                } else {
                    others.add(memory);
                }
            }
            int ruleSlots = Math.max(properties.getRuleTopK(), 0);
            List<AgentMemory> chosenRules = rules.subList(0, Math.min(rules.size(), ruleSlots));
            int remainSlots = Math.max(properties.getTopK() - chosenRules.size(), 0);
            List<AgentMemory> chosenOthers = others.subList(0, Math.min(others.size(), remainSlots));
            List<String> result = new ArrayList<>(chosenRules.size() + chosenOthers.size());
            chosenRules.forEach(item -> result.add(format(item)));
            chosenOthers.forEach(item -> result.add(format(item)));
            return result;
        } catch (RuntimeException e) {
            log.warn("AgentMemoryService#recall#降级为空 namespace={} owner={} err={}",
                    namespace, owner, e.toString());
            return List.of();
        }
    }

    private String format(AgentMemory memory) {
        String shared = memory.getOwner() == null ? "[共享] " : "";
        String body = switch (memory.getKind() == null ? "" : memory.getKind()) {
            case AgentMemory.KIND_RULE -> "规则（调用方纠正过，必须遵守）：" + memory.getContent();
            case AgentMemory.KIND_PREFERENCE -> "偏好：" + memory.getContent();
            case AgentMemory.KIND_HABIT -> "表达习惯：" + memory.getContent();
            default -> "稳定事实：" + memory.getContent();
        };
        return shared + body;
    }

    // ---------------- 提炼（写路径，异步） ----------------

    /**
     * 一轮对话结束后提交提炼。不阻塞对话主流程；任务被拒（队列满/关闭）只留日志。
     *
     * <p>分区与归属方必须显式传：这里用的是裸线程池（没有 TTL 包装），
     * 上下文传不进来，靠 {@code RunContext} 取会在异步线程拿到 null。</p>
     */
    public void consolidateAsync(String namespace, String owner, Long sessionId,
                                 String userMessage, String assistantReply) {
        if (!properties.isEnabled() || !aiProperties.isEnabled()
                || !StringUtils.hasText(namespace) || !StringUtils.hasText(owner)) {
            return;
        }
        if (!StringUtils.hasText(userMessage) || !StringUtils.hasText(assistantReply)) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    // 同一归属方同一时刻只允许一次提炼：两个 Pod 各读一遍旧列表、各 ADD 一条
                    // 意思相近的记忆，去重（逐字比对）挡不住这种并发。
                    // 拿不到锁就跳过，下一轮对话再提炼，不排队。
                    boolean ran = lockTemplate.tryRunOnce(KitKeys.memoryLock(namespace, owner), 0L, -1L,
                            () -> consolidate(namespace, owner, sessionId, userMessage, assistantReply));
                    if (!ran) {
                        log.info("AgentMemoryService#consolidateAsync#另一实例正在提炼，本轮跳过 owner={}", owner);
                    }
                } catch (RuntimeException e) {
                    log.warn("AgentMemoryService#consolidate#提炼失败 owner={} err={}", owner, e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("AgentMemoryService#consolidateAsync#任务被拒 owner={}", owner);
        }
    }

    /** 同步提炼（单测可直调）：拿现有记忆（个人 + 共享）→ 模型出 ops → 逐条应用 */
    void consolidate(String namespace, String owner, Long sessionId, String userMessage, String assistantReply) {
        List<AgentMemory> existing = listVisible(namespace, owner);
        String existingText = existing.isEmpty() ? "（暂无）" : existing.stream()
                .map(item -> item.getId() + ". [" + item.getKind()
                        + (item.getOwner() == null ? "|共享]" : "]")
                        + " " + item.getContent())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("（暂无）");
        String prompt = buildPrompt(existingText, userMessage, assistantReply);

        ModelReply reply;
        try {
            reply = chatModel().chat(List.of(ChatMessage.system(prompt)), true);
        } catch (RuntimeException e) {
            // 提炼模型不可用（如本地 Ollama 掉线）：本轮放弃，下一轮对话大概率再触发同样的信息。
            // 不回落到云端主渠道——配置了省钱渠道又默默烧云费用，等于配置失效。
            log.warn("AgentMemoryService#consolidate#提炼模型不可用，本轮跳过 provider={} err={}",
                    properties.getChatProvider(), e.toString());
            return;
        }
        List<Op> ops = parseOps(reply == null ? null : reply.content());
        if (ops.isEmpty()) {
            return;
        }
        log.info("AgentMemoryService#consolidate#提炼 owner={} sessionId={} ops={} 已有={}",
                owner, sessionId, ops.size(), existing.size());
        for (Op op : ops) {
            try {
                apply(namespace, owner, sessionId, existing, op);
            } catch (RuntimeException e) {
                log.warn("AgentMemoryService#consolidate#单条应用失败 owner={} op={} err={}",
                        owner, op, e.toString());
            }
        }
    }

    /**
     * 渲染提炼提示词。
     *
     * <p>模板可由业务整体替换（{@code agent-kit.memory.consolidate-prompt}）。
     * 自定义模板里的 {@code %} 写错会让 {@code formatted} 抛异常，此时退回默认模板——
     * 记不住东西是小事，让异步线程抛异常把后续轮次全卡住才是大事。</p>
     */
    private String buildPrompt(String existingText, String userMessage, String assistantReply) {
        String template = StringUtils.hasText(properties.getConsolidatePrompt())
                ? properties.getConsolidatePrompt() : MemoryProperties.DEFAULT_CONSOLIDATE_PROMPT;
        try {
            return template.formatted(LocalDate.now(), existingText,
                    truncate(userMessage, 1000), truncate(assistantReply, 1000));
        } catch (RuntimeException e) {
            log.warn("AgentMemoryService#buildPrompt#自定义模板格式非法，退回默认模板 err={}", e.toString());
            return MemoryProperties.DEFAULT_CONSOLIDATE_PROMPT.formatted(LocalDate.now(), existingText,
                    truncate(userMessage, 1000), truncate(assistantReply, 1000));
        }
    }

    /**
     * 提炼用的模型渠道：配置了 {@code chatProvider} 就定点用（后台任务专用渠道，
     * 默认本地 ollama），没配就沿用对话主链路的提供方（含主备组合）。
     */
    private ModelProvider chatModel() {
        String id = properties.getChatProvider();
        if (!StringUtils.hasText(id)) {
            return providerRegistry.current();
        }
        return providerRegistry.byId(id);
    }

    private void apply(String namespace, String owner, Long sessionId, List<AgentMemory> existing, Op op) {
        switch (op.action) {
            case "ADD" -> {
                String content = normalize(op.content);
                String kind = normalizeKind(op.kind);
                if (content == null) {
                    return;
                }
                // 共享与否只看 op 的 shared 标记：owner 为 null 即分区内共享
                String targetOwner = op.shared ? null : owner;
                // 去重：与现有记忆逐字重复的不写
                boolean duplicated = existing.stream()
                        .anyMatch(item -> item.getContent().equals(content));
                if (duplicated) {
                    log.info("AgentMemoryService#apply#跳过重复记忆 owner={}", owner);
                    return;
                }
                var counter = Wrappers.<AgentMemory>lambdaQuery()
                        .eq(AgentMemory::getNamespace, namespace)
                        .eq(AgentMemory::getStatus, AgentMemory.STATUS_ACTIVE);
                if (targetOwner == null) {
                    counter.isNull(AgentMemory::getOwner);
                } else {
                    counter.eq(AgentMemory::getOwner, targetOwner);
                }
                long count = memoryMapper.selectCount(counter);
                if (count >= properties.getMaxPerOwner()) {
                    log.warn("AgentMemoryService#apply#记忆已达上限 owner={} shared={} max={}", owner,
                            targetOwner == null, properties.getMaxPerOwner());
                    return;
                }
                // 先向量化再落库：嵌入失败整条跳过，下一轮对话大概率还会触发同样的信息
                float[] vector = embeddingClient.embed(content);
                AgentMemory memory = new AgentMemory();
                memory.setNamespace(namespace);
                memory.setOwner(targetOwner);
                memory.setKind(kind);
                memory.setContent(content);
                memory.setSourceSessionId(sessionId);
                memory.setStatus(AgentMemory.STATUS_ACTIVE);
                memoryMapper.insert(memory);
                vectorStore.upsert(memory.getId(), namespace, targetOwner, vector);
                existing.add(memory);
                log.info("AgentMemoryService#apply#新增记忆 id={} kind={} shared={} content={}",
                        memory.getId(), kind, targetOwner == null, content);
            }
            case "UPDATE" -> {
                AgentMemory old = owned(existing, owner, op.id);
                if (old == null) {
                    return;
                }
                String content = normalize(op.content);
                if (content == null || content.equals(old.getContent())) {
                    return;
                }
                // 共享状态继承旧记忆，除非 op 明确要求改成共享
                boolean shared = op.shared || old.getOwner() == null;
                float[] vector = embeddingClient.embed(content);
                AgentMemory memory = new AgentMemory();
                // 归属继承旧记忆：提炼线程里没有请求上下文，显式写死比依赖上下文可靠
                memory.setNamespace(namespace);
                memory.setOwner(shared ? null : owner);
                memory.setKind(normalizeKind(op.kind));
                memory.setContent(content);
                memory.setSourceSessionId(sessionId);
                memory.setStatus(AgentMemory.STATUS_ACTIVE);
                memoryMapper.insert(memory);
                supersede(old);
                vectorStore.upsert(memory.getId(), namespace, memory.getOwner(), vector);
                existing.remove(old);
                existing.add(memory);
                log.info("AgentMemoryService#apply#更新记忆 old={} new={} shared={} content={}",
                        op.id, memory.getId(), shared, content);
            }
            case "DROP" -> {
                AgentMemory old = owned(existing, owner, op.id);
                if (old == null) {
                    return;
                }
                supersede(old);
                existing.remove(old);
                log.info("AgentMemoryService#apply#废弃记忆 id={} reason={}", op.id, op.reason);
            }
            default -> log.warn("AgentMemoryService#apply#未知动作 action={}", op.action);
        }
    }

    /** 旧记忆置 SUPERSEDED（MySQL 先落，Milvus 向量随后删；删失败也不影响，检索按状态过滤兜底） */
    private void supersede(AgentMemory old) {
        AgentMemory patch = new AgentMemory();
        patch.setId(old.getId());
        patch.setStatus(AgentMemory.STATUS_SUPERSEDED);
        memoryMapper.updateById(patch);
        try {
            vectorStore.delete(old.getId());
        } catch (RuntimeException e) {
            log.warn("AgentMemoryService#supersede#向量删除失败（检索按状态过滤兜底） id={} err={}",
                    old.getId(), e.toString());
        }
    }

    /**
     * 列出本次可参与提炼的记忆（本人 + 分区共享）。
     *
     * <p>分区条件必须显式写：提炼跑在裸线程池里，没有请求上下文，
     * 只靠拦截器就会把别的分区的记忆读进来并参与冲突判定。</p>
     *
     * <p><b>必须返回可变副本</b>：{@link #apply} 会在批内就地 add/remove 来维持「同一轮多条 op 看到彼此的
     * 结果」，而 mapper 返回的集合类型不受本类控制（MyBatis 目前给 ArrayList，但任何不可变视图——
     * 包装器、缓存返回值、测试替身——都会让 add 抛 UnsupportedOperationException）。
     * 该异常会被 per-op 的 catch 吞掉，表现为「同批次后续记忆静默丢失」，排查成本极高。</p>
     */
    private List<AgentMemory> listVisible(String namespace, String owner) {
        List<AgentMemory> rows = memoryMapper.selectList(Wrappers.<AgentMemory>lambdaQuery()
                .eq(AgentMemory::getNamespace, namespace)
                .eq(AgentMemory::getStatus, AgentMemory.STATUS_ACTIVE)
                .and(w -> w.eq(AgentMemory::getOwner, owner)
                        .or().isNull(AgentMemory::getOwner))
                .orderByAsc(AgentMemory::getId));
        return rows == null ? new ArrayList<>() : new ArrayList<>(rows);
    }

    private AgentMemory owned(List<AgentMemory> existing, String owner, Long id) {
        if (id == null) {
            return null;
        }
        return existing.stream()
                .filter(item -> id.equals(item.getId()))
                // 本人记忆必须属于本人；共享记忆任何调用方都能改（分区级口径是共同财产）
                .filter(item -> owner.equals(item.getOwner()) || item.getOwner() == null)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("AgentMemoryService#owned#记忆不存在或不可见 owner={} id={}", owner, id);
                    return null;
                });
    }

    // ---------------- 自愈重建 ----------------

    /**
     * 合并同一归属方（或分区共享）的 RULE 记忆（每日自检的规则去重用）：
     * {@code dropIds} 全部置 SUPERSEDED，{@code content} 作为合并后的新规则落库；
     * 被合并规则里含共享记忆的，合并结果保持共享。
     *
     * <p>整组校验、整组执行：任何一个 id 不属于该归属方 / 不是 RULE / 已废弃，整组放弃——
     * 合并是减少碎片化的优化动作，宁可不做也不能把别人的或已生效的规则合错。</p>
     *
     * @return true 表示合并完成（新规则落库、旧规则全部废弃）
     */
    public boolean mergeRules(String namespace, String owner, List<Long> dropIds, String content) {
        String merged = normalize(content);
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(owner)
                || dropIds == null || dropIds.isEmpty() || merged == null) {
            return false;
        }
        List<AgentMemory> drops = memoryMapper.selectList(Wrappers.<AgentMemory>lambdaQuery()
                        .eq(AgentMemory::getNamespace, namespace)
                        .in(AgentMemory::getId, dropIds)).stream()
                .filter(item -> owner.equals(item.getOwner()) || item.getOwner() == null)
                .filter(item -> AgentMemory.KIND_RULE.equals(item.getKind()))
                .filter(item -> AgentMemory.STATUS_ACTIVE.equals(item.getStatus()))
                .toList();
        if (drops.size() != dropIds.size()) {
            log.warn("AgentMemoryService#mergeRules#候选不完整，整组放弃 owner={} 期望={} 实际={}",
                    owner, dropIds.size(), drops.size());
            return false;
        }
        // 合并结果继承被合并规则的作用域：含共享规则则合并后仍共享
        boolean shared = drops.stream().anyMatch(item -> item.getOwner() == null);
        try {
            float[] vector = embeddingClient.embed(merged);
            AgentMemory memory = new AgentMemory();
            memory.setNamespace(namespace);
            memory.setOwner(shared ? null : owner);
            memory.setKind(AgentMemory.KIND_RULE);
            memory.setContent(merged);
            memory.setStatus(AgentMemory.STATUS_ACTIVE);
            memoryMapper.insert(memory);
            vectorStore.upsert(memory.getId(), namespace, memory.getOwner(), vector);
            drops.forEach(this::supersede);
            log.info("AgentMemoryService#mergeRules#规则合并完成 owner={} 新id={} 合并数={}",
                    owner, memory.getId(), drops.size());
            return true;
        } catch (RuntimeException e) {
            log.warn("AgentMemoryService#mergeRules#合并失败 owner={} err={}", owner, e.toString());
            return false;
        }
    }

    /**
     * 新增一条<b>分区共享</b>的记忆（供定时任务/自检把「可自愈缺口」教成规则）：
     * 学到的口径写成共享 RULE，分区内所有调用方的对话都受益。
     *
     * @param kind    记忆类型（RULE / PREFERENCE，其他归 FACT）
     * @param content 一句话规则，必须脱离上下文可懂
     * @return true 表示落库成功
     */
    public boolean addSharedMemory(String namespace, String kind, String content) {
        String normalized = normalize(content);
        if (!StringUtils.hasText(namespace) || normalized == null) {
            return false;
        }
        // 去重：本分区已有同样内容的不重复教（按分区查——别的分区有同样的规则不代表这里有）
        long dup = memoryMapper.selectCount(Wrappers.<AgentMemory>lambdaQuery()
                .eq(AgentMemory::getNamespace, namespace)
                .isNull(AgentMemory::getOwner)
                .eq(AgentMemory::getStatus, AgentMemory.STATUS_ACTIVE)
                .eq(AgentMemory::getContent, normalized));
        if (dup > 0) {
            log.info("AgentMemoryService#addSharedMemory#分区已有同样记忆，跳过 namespace={} content={}",
                    namespace, normalized);
            return false;
        }
        long count = memoryMapper.selectCount(Wrappers.<AgentMemory>lambdaQuery()
                .eq(AgentMemory::getNamespace, namespace)
                .isNull(AgentMemory::getOwner)
                .eq(AgentMemory::getStatus, AgentMemory.STATUS_ACTIVE));
        if (count >= properties.getMaxPerOwner()) {
            log.warn("AgentMemoryService#addSharedMemory#共享记忆已达上限 namespace={} max={}",
                    namespace, properties.getMaxPerOwner());
            return false;
        }
        try {
            float[] vector = embeddingClient.embed(normalized);
            AgentMemory memory = new AgentMemory();
            memory.setNamespace(namespace);
            memory.setOwner(null);
            memory.setKind(normalizeKind(kind));
            memory.setContent(normalized);
            memory.setStatus(AgentMemory.STATUS_ACTIVE);
            memoryMapper.insert(memory);
            vectorStore.upsert(memory.getId(), namespace, null, vector);
            log.info("AgentMemoryService#addSharedMemory#新增共享记忆 namespace={} id={} kind={} content={}",
                    namespace, memory.getId(), memory.getKind(), normalized);
            return true;
        } catch (RuntimeException e) {
            log.warn("AgentMemoryService#addSharedMemory#写入失败 namespace={} err={}", namespace, e.toString());
            return false;
        }
    }

    /**
     * 按 MySQL 表重建 Milvus 索引（丢库/换库/collection 结构重建后调用），返回重建条数。
     * MySQL 是事实源：ACTIVE 的记忆逐条向量化灌回 Milvus。
     *
     * <p>跨分区全量灌：分区与归属取自实体自身，而不是当前上下文——重建是运维动作，
     * 通常发生在没有请求上下文的启动/自愈路径上。</p>
     */
    public int rebuildFromMysql() {
        List<AgentMemory> all = memoryMapper.selectList(Wrappers.<AgentMemory>lambdaQuery()
                .eq(AgentMemory::getStatus, AgentMemory.STATUS_ACTIVE));
        int rebuilt = 0;
        for (AgentMemory memory : all) {
            if (!StringUtils.hasText(memory.getNamespace())) {
                // 缺分区的行灌进索引也检索不出来，跳过并留痕，避免脏数据反复占索引
                log.warn("AgentMemoryService#rebuildFromMysql#记忆缺分区，跳过 id={}", memory.getId());
                continue;
            }
            try {
                float[] vector = embeddingClient.embed(memory.getContent());
                vectorStore.upsert(memory.getId(), memory.getNamespace(), memory.getOwner(), vector);
                rebuilt++;
            } catch (RuntimeException e) {
                log.warn("AgentMemoryService#rebuildFromMysql#单条失败 id={} err={}",
                        memory.getId(), e.toString());
            }
        }
        log.info("AgentMemoryService#rebuildFromMysql#完成 total={} rebuilt={}", all.size(), rebuilt);
        return rebuilt;
    }

    // ---------------- 内部工具 ----------------

    private List<Op> parseOps(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JSONArray array = JSON.parseObject(raw.substring(start, end + 1)).getJSONArray("ops");
            if (array == null) {
                return List.of();
            }
            List<Op> ops = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                ops.add(new Op(
                        item.getString("action"),
                        item.getLong("id"),
                        item.getString("kind"),
                        item.getString("content"),
                        item.getString("reason"),
                        Boolean.TRUE.equals(item.getBoolean("shared"))));
            }
            return ops;
        } catch (RuntimeException e) {
            log.warn("AgentMemoryService#parseOps#解析失败 raw={}", abbreviate(raw));
            return List.of();
        }
    }

    private String normalize(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.isEmpty() ? null : (flat.length() <= 1000 ? flat : flat.substring(0, 1000));
    }

    private String normalizeKind(String kind) {
        if (AgentMemory.KIND_PREFERENCE.equals(kind) || AgentMemory.KIND_HABIT.equals(kind)
                || AgentMemory.KIND_RULE.equals(kind)) {
            return kind;
        }
        return AgentMemory.KIND_FACT;
    }

    private String truncate(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private String abbreviate(String raw) {
        String flat = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 模型输出的一条记忆操作 */
    record Op(String action, Long id, String kind, String content, String reason, boolean shared) {
    }
}
