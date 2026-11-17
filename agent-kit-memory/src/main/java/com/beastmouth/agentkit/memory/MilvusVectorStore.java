package com.beastmouth.agentkit.memory;

import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Milvus 向量索引：只存 (id, namespace, owner, vector)，正文在 MySQL。
 *
 * <p><b>两个标量字段的语义</b>：{@code namespace} 是隔离边界，检索必须先按它过滤，
 * 否则 A 分区的语义搜索会命中 B 分区的记忆；{@code owner} 是归属，个人记忆存归属方，
 * 共享记忆存空串（向量库标量字段不支持 null），由调用方（{@link AgentMemoryService}）
 * 负责按 MySQL 的 owner 精过滤。</p>
 *
 * <p>定位是「可重建的检索索引」而不是事实源——collection 被误删、Milvus 重装、
 * 结构过期，按 {@code ai_agent_memory} 表重新灌一遍就恢复
 * （见 {@link #takeLegacyResetSignal()}）。连接惰性建立：Milvus 没起不影响应用启动，
 * 第一次用到记忆功能才连，失败抛异常由调用方降级。</p>
 */
@Slf4j
@Component
public class MilvusVectorStore implements DisposableBean {

    /** 分区标量字段名 */
    private static final String FIELD_NAMESPACE = "namespace";
    /** 归属标量字段名 */
    private static final String FIELD_OWNER = "owner";
    /** VARCHAR 上限：分区名与归属标识都远小于此值，超长由调用方截断 */
    private static final int NAMESPACE_MAX_LENGTH = 128;
    private static final int OWNER_MAX_LENGTH = 128;

    private final MemoryProperties properties;

    private volatile MilvusClientV2 client;
    private volatile boolean collectionReady;

    /**
     * 「索引结构过期已重建」信号：老结构的 collection 没有 namespace 字段，
     * 只能 drop 重建，于是索引里一条向量都没有。置位后由调用方（{@link AgentMemoryService#recall}）
     * 按 MySQL 重建一次；{@link AtomicBoolean#compareAndSet} 保证多线程只触发一次。
     */
    private final AtomicBoolean legacyReset = new AtomicBoolean(false);

    public MilvusVectorStore(MemoryProperties properties) {
        this.properties = properties;
    }

    /**
     * 确保连接与 collection 结构就绪。
     *
     * <p>给调用方一个「先确保结构再问是否重建」的入口：{@link #takeLegacyResetSignal()}
     * 只有在 {@code ready()} 跑过之后才可能为真，所以顺序不能反。</p>
     */
    public void ensureReady() {
        ready();
    }

    /**
     * 取走「结构过期已重建」信号（一次性）。
     *
     * @return true 表示索引刚被清空，调用方应立即按 MySQL 重建
     */
    public boolean takeLegacyResetSignal() {
        return legacyReset.compareAndSet(true, false);
    }

    /**
     * 写入一条向量（id 即 MySQL 主键）。
     *
     * @param owner 归属方；{@code null} 表示分区内共享，索引里写空串
     */
    public void upsert(Long id, String namespace, String owner, float[] vector) {
        MilvusClientV2 c = ready();
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty(FIELD_NAMESPACE, truncate(namespace, NAMESPACE_MAX_LENGTH));
        row.addProperty(FIELD_OWNER, ownerOf(owner));
        com.google.gson.JsonArray vec = new com.google.gson.JsonArray(vector.length);
        for (float v : vector) {
            vec.add(v);
        }
        row.add("vector", vec);
        c.insert(InsertReq.builder()
                .collectionName(properties.getCollection())
                .data(Collections.singletonList(row))
                .build());
    }

    /**
     * 按「分区 + 归属」过滤检索最相似的若干条，返回 (id, 相似度)，无关条目由调用方再过滤。
     *
     * <p>分区条件是<b>硬边界</b>：两个分区可能都有 owner 为空的共享记忆行，
     * 只按 owner 过滤会把别的分区的规则捞出来，而且捞出来的是「很像」的内容，
     * 混进提示词后模型会当真照着执行。</p>
     */
    public List<Hit> search(String namespace, String owner, float[] vector, int topK) {
        MilvusClientV2 c = ready();
        String filter = FIELD_NAMESPACE + " == \"" + escape(namespace) + "\""
                + " && " + FIELD_OWNER + " == \"" + escape(ownerOf(owner)) + "\"";
        SearchResp resp = c.search(SearchReq.builder()
                .collectionName(properties.getCollection())
                .data(Collections.singletonList(new FloatVec(vector)))
                .annsField("vector")
                .topK(topK)
                .filter(filter)
                .outputFields(Collections.singletonList("id"))
                .build());
        List<Hit> hits = new ArrayList<>();
        for (List<SearchResp.SearchResult> group : resp.getSearchResults()) {
            for (SearchResp.SearchResult result : group) {
                Object idValue = result.getEntity() == null ? null : result.getEntity().get("id");
                if (idValue instanceof Number number) {
                    hits.add(new Hit(number.longValue(), result.getScore()));
                }
            }
        }
        return hits;
    }

    /** 删除向量（记忆被 UPDATE/DROP 时调用，索引里不留废弃内容） */
    public void delete(Long id) {
        MilvusClientV2 c = ready();
        c.delete(DeleteReq.builder()
                .collectionName(properties.getCollection())
                .filter("id == " + id)
                .build());
    }

    private MilvusClientV2 ready() {
        MilvusClientV2 c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                            .uri(properties.getMilvusUri())
                            .connectTimeoutMs(10_000);
                    // Milvus 开了 authorization 之后，匿名连接会在建连阶段就被拒
                    // （UNAUTHENTICATED），记忆检索会一直空列表。用户名留空时保持
                    // 旧的匿名行为，方便未开认证的环境直接复用同一份配置。
                    if (StringUtils.hasText(properties.getMilvusUsername())) {
                        builder.username(properties.getMilvusUsername())
                                .password(properties.getMilvusPassword());
                    }
                    client = new MilvusClientV2(builder.build());
                    log.info("MilvusVectorStore#connected uri={} collection={}",
                            properties.getMilvusUri(), properties.getCollection());
                }
                c = client;
            }
        }
        ensureCollection(c);
        return c;
    }

    private void ensureCollection(MilvusClientV2 c) {
        if (collectionReady) {
            return;
        }
        synchronized (this) {
            if (collectionReady) {
                return;
            }
            String name = properties.getCollection();
            Boolean exists = c.hasCollection(HasCollectionReq.builder().collectionName(name).build());
            if (Boolean.TRUE.equals(exists) && !hasScalarFields(c, name)) {
                // 旧结构（没有 namespace/owner）在分区隔离下是错的：两个分区都有 owner 为空的
                // 共享记忆，过滤不出来源。Milvus 不支持加完字段立即按新字段过滤，
                // 而这张表本来就只是「可重建的索引」，直接 drop 重建最省事。
                // 重建后索引为空，由 takeLegacyResetSignal 通知调用方按 MySQL 灌回。
                c.dropCollection(DropCollectionReq.builder().collectionName(name).build());
                log.warn("MilvusVectorStore#ensureCollection#旧结构缺少 {} / {} 字段，已删除待重建 name={}",
                        FIELD_NAMESPACE, FIELD_OWNER, name);
                legacyReset.set(true);
                exists = Boolean.FALSE;
            }
            if (!Boolean.TRUE.equals(exists)) {
                createCollection(c, name);
            }
            collectionReady = true;
        }
    }

    private boolean hasScalarFields(MilvusClientV2 c, String name) {
        try {
            DescribeCollectionResp resp = c.describeCollection(
                    DescribeCollectionReq.builder().collectionName(name).build());
            List<String> fields = resp == null ? null : resp.getFieldNames();
            return fields != null && fields.contains(FIELD_NAMESPACE) && fields.contains(FIELD_OWNER);
        } catch (RuntimeException e) {
            // 查不到结构（Milvus 版本差异/权限受限）时按「缺字段」处理：
            // 宁可重建一次索引，也不能带着没有分区隔离的结构继续跑
            log.warn("MilvusVectorStore#hasScalarFields#查结构失败，按缺字段处理 name={} err={}",
                    name, e.toString());
            return false;
        }
    }

    private void createCollection(MilvusClientV2 c, String name) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.Int64)
                .isPrimaryKey(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_NAMESPACE).dataType(DataType.VarChar)
                .maxLength(NAMESPACE_MAX_LENGTH).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_OWNER).dataType(DataType.VarChar)
                .maxLength(OWNER_MAX_LENGTH).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("vector").dataType(DataType.FloatVector)
                .dimension(properties.getDimensions()).build());
        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(name)
                .collectionSchema(schema)
                .indexParams(List.of(IndexParam.builder()
                        .fieldName("vector")
                        .indexType(IndexParam.IndexType.AUTOINDEX)
                        .metricType(IndexParam.MetricType.COSINE)
                        .build()))
                .build();
        // drop 是元数据操作，立刻 create 偶发「集合仍存在」：重试几次比让整段记忆功能起不来好
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                c.createCollection(createReq);
                log.info("MilvusVectorStore#createCollection#已创建 name={} dim={} attempt={}",
                        name, properties.getDimensions(), attempt);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (Boolean.TRUE.equals(c.hasCollection(
                        HasCollectionReq.builder().collectionName(name).build()))) {
                    // 多副本同时首次启动：别的实例已经建好了，这不是故障
                    log.info("MilvusVectorStore#createCollection#集合已由其他实例创建 name={}", name);
                    return;
                }
                sleepQuietly(200L);
            }
        }
        throw last == null ? new IllegalStateException("create collection failed: " + name) : last;
    }

    /** 向量库标量字段不支持 null：共享记忆的归属统一写空串 */
    private static String ownerOf(String owner) {
        return owner == null ? "" : truncate(owner, OWNER_MAX_LENGTH);
    }

    /** 过滤表达式是拼接出来的，双引号必须转义，否则一个带引号的分区名就能破坏表达式 */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void destroy() {
        MilvusClientV2 c = client;
        if (c != null) {
            c.close();
        }
    }

    /** 检索命中：MySQL 主键 + 相似度（COSINE，越大越像） */
    public record Hit(Long id, float score) {
    }
}
