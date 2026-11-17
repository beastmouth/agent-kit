package com.beastmouth.agentkit.tools.milvus;

import com.beastmouth.agentkit.memory.MemoryProperties;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预建 / 校验 Milvus 记忆 collection 的运维入口（命令行）。
 *
 * <p>应用侧（{@code MilvusVectorStore}）在第一次用到记忆功能时会按配置自动建 collection，
 * 本工具只在两种场景下需要：</p>
 * <ol>
 *   <li><b>生产治理</b>：DDL 由运维在发版前执行，不交给应用首启后的第一个请求；</li>
 *   <li><b>换嵌入模型 / 改维度</b>：{@code --drop} 重建（Milvus 只是可重建索引，
 *       重建后由应用按 MySQL 灌回 {@code ai_agent_memory}，正文无损）。</li>
 * </ol>
 *
 * <p><b>结构必须与 {@code MilvusVectorStore#createCollection()} 完全一致</b>：
 * id(INT64 主键) / namespace(VARCHAR 128) / owner(VARCHAR 128) / vector(FLOAT_VECTOR)，
 * 索引 AUTOINDEX + COSINE。应用首次检索时会校验结构，缺 namespace / owner 字段的
 * collection 会被判定为旧结构直接 drop 重建——所以字段名与长度不要自作主张改。</p>
 *
 * <p>复用 {@code MemoryProperties} 的默认值，让工具与应用对同一份默认口径负责；
 * 参数优先级：显式参数 &gt; 环境变量（{@code MILVUS_URI} / {@code MILVUS_COLLECTION} /
 * {@code MILVUS_DIMENSIONS} / {@code MILVUS_USERNAME} / {@code MILVUS_PASSWORD}）&gt; 默认值。
 * 账密务必走环境变量，不要写进任何提交到仓库的脚本、流水线文件或文档。</p>
 *
 * <p>构建：{@code mvn -Ptools clean package}（默认构建不含本模块）。
 * 运行：{@code java -jar agent-kit-tools-0.1.0.jar --uri http://localhost:19530 --collection myapp_memory}
 * 或 {@code java -cp agent-kit-tools-0.1.0.jar com.beastmouth.agentkit.tools.milvus.MilvusCollectionTool --help}。</p>
 *
 * <p>退出码：{@code 0} 成功或已存在且结构一致；{@code 1} 结构不符被拒绝、连接失败等错误——
 * 方便接进发布流水线做闸门。</p>
 */
@Slf4j
public class MilvusCollectionTool {

    /** 与 MilvusVectorStore 的常量保持一致，改这里之前先改 Java 存储类 */
    private static final int NAMESPACE_MAX_LENGTH = 128;
    private static final int OWNER_MAX_LENGTH = 128;
    private static final Set<String> REQUIRED_FIELDS = Set.of("id", "namespace", "owner", "vector");

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (RuntimeException e) {
            System.err.println("[失败] " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) {
        Options opts = Options.parse(args);
        MemoryProperties props = opts.toMemoryProperties();

        MilvusClientV2 client = connect(props);
        try {
            return ensureCollection(client, props, opts.drop);
        } finally {
            client.close();
        }
    }

    // ---------------- 连接 ----------------

    private static MilvusClientV2 connect(MemoryProperties props) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(props.getMilvusUri())
                .connectTimeoutMs(10_000);
        if (StringUtils.hasText(props.getMilvusUsername())) {
            builder.username(props.getMilvusUsername()).password(props.getMilvusPassword());
        }
        MilvusClientV2 client = new MilvusClientV2(builder.build());
        System.out.println("[连接] uri=" + props.getMilvusUri());
        return client;
    }

    // ---------------- 建 / 校验 ----------------

    private static int ensureCollection(MilvusClientV2 client, MemoryProperties props, boolean drop) {
        String name = props.getCollection();
        Boolean exists = client.hasCollection(HasCollectionReq.builder().collectionName(name).build());

        if (Boolean.TRUE.equals(exists)) {
            DescribeResult actual = describe(client, name);
            if (actual.matches(props.getDimensions())) {
                System.out.println("[跳过] collection 已存在且结构一致 name=" + name
                        + " dim=" + actual.dim + " fields=" + actual.fields);
                return 0;
            }
            if (!drop) {
                // 不相符就拒绝：这可能是「别的系统在用」或「老结构」，删错没有回头路。
                // 需要重建时由人确认后显式加 --drop，工具不替人做这个决定。
                System.err.println("[拒绝] collection 已存在但结构不符 name=" + name
                        + " fields=" + actual.fields + " dim=" + actual.dim);
                System.err.println("       本工具要求的结构：字段 " + REQUIRED_FIELDS
                        + "，dim=" + props.getDimensions());
                System.err.println("       确认无需保留后加 --drop 重建"
                        + "（索引可按 MySQL 灌回，见 docs/09-Milvus接入 §6）");
                return 1;
            }
            client.dropCollection(DropCollectionReq.builder().collectionName(name).build());
            System.out.println("[重建] 已删除旧 collection name=" + name);
        }

        create(client, name, props.getDimensions());
        DescribeResult after = describe(client, name);
        System.out.println("[完成] name=" + name + " dim=" + after.dim + " fields=" + after.fields);
        System.out.println("       记得把同一名字写进配置：agent-kit.memory.collection: " + name);
        return 0;
    }

    private static void create(MilvusClientV2 client, String name, int dimensions) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("namespace").dataType(DataType.VarChar)
                .maxLength(NAMESPACE_MAX_LENGTH).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("owner").dataType(DataType.VarChar)
                .maxLength(OWNER_MAX_LENGTH).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("vector").dataType(DataType.FloatVector)
                .dimension(dimensions).build());
        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(name)
                .collectionSchema(schema)
                .indexParams(List.of(IndexParam.builder()
                        .fieldName("vector")
                        .indexType(IndexParam.IndexType.AUTOINDEX)
                        .metricType(IndexParam.MetricType.COSINE)
                        .build()))
                .build();

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                client.createCollection(createReq);
                System.out.println("[创建] name=" + name + " dim=" + dimensions + " attempt=" + attempt);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (Boolean.TRUE.equals(client.hasCollection(
                        HasCollectionReq.builder().collectionName(name).build()))) {
                    System.out.println("[创建] 集合已由其他实例创建 name=" + name);
                    return;
                }
                sleepQuietly(200L);
            }
        }
        throw last == null ? new IllegalStateException("create collection failed: " + name) : last;
    }

    private record DescribeResult(Set<String> fields, Integer dim) {
        boolean matches(int expectedDim) {
            return REQUIRED_FIELDS.equals(fields) && dim != null && dim == expectedDim;
        }
    }

    private static DescribeResult describe(MilvusClientV2 client, String name) {
        DescribeCollectionResp resp = client.describeCollection(
                DescribeCollectionReq.builder().collectionName(name).build());
        Set<String> fields = resp.getFieldNames() == null ? Set.of() : Set.copyOf(resp.getFieldNames());
        Integer dim = null;
        for (var field : resp.getCollectionSchema().getFieldSchemaList()) {
            if (!"vector".equals(field.getName())) {
                continue;
            }
            Object value = field.getTypeParams().get("dim");
            if (value instanceof Map<?, ?> map) {
                value = map.get("dim");
            }
            if (value instanceof Number number) {
                dim = number.intValue();
            } else if (value instanceof String text && !text.isBlank()) {
                dim = Integer.valueOf(text.trim());
            }
        }
        return new DescribeResult(fields, dim);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- 参数解析 ----------------

    /**
     * 命令行参数。优先级：显式参数 &gt; 环境变量 &gt; MemoryProperties 默认值。
     *
     * <p>账密支持 {@code --username/--password} 与 {@code MILVUS_USERNAME/MILVUS_PASSWORD}
     * 环境变量两条路。优先用环境变量：命令行参数会进 shell 历史、CI 日志和 {@code ps} 输出。</p>
     */
    private static final class Options {

        private String uri;
        private String collection;
        private Integer dimensions;
        private String username;
        private String password;
        private boolean drop;

        static Options parse(String[] args) {
            Options opts = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--uri" -> opts.uri = next(args, ++i, "--uri");
                    case "--collection" -> opts.collection = next(args, ++i, "--collection");
                    case "--dimensions" -> opts.dimensions = Integer.valueOf(next(args, ++i, "--dimensions"));
                    case "--username" -> opts.username = next(args, ++i, "--username");
                    case "--password" -> opts.password = next(args, ++i, "--password");
                    case "--drop" -> opts.drop = true;
                    case "--help", "-h" -> {
                        printUsage();
                        System.exit(0);
                    }
                    default -> {
                        System.err.println("未知参数：" + args[i]);
                        printUsage();
                        System.exit(1);
                    }
                }
            }
            // 显式参数没给的，回落到环境变量（账密等敏感值推荐走这条路）
            if (opts.uri == null) {
                opts.uri = env("MILVUS_URI");
            }
            if (opts.collection == null) {
                opts.collection = env("MILVUS_COLLECTION");
            }
            if (opts.dimensions == null && env("MILVUS_DIMENSIONS") != null) {
                opts.dimensions = Integer.valueOf(env("MILVUS_DIMENSIONS"));
            }
            if (opts.username == null) {
                opts.username = env("MILVUS_USERNAME");
            }
            if (opts.password == null) {
                opts.password = env("MILVUS_PASSWORD");
            }
            return opts;
        }

        private static String env(String name) {
            String value = System.getenv(name);
            return StringUtils.hasText(value) ? value : null;
        }

        MemoryProperties toMemoryProperties() {
            MemoryProperties props = new MemoryProperties();
            if (StringUtils.hasText(uri)) {
                props.setMilvusUri(uri);
            }
            if (StringUtils.hasText(collection)) {
                props.setCollection(collection);
            }
            if (dimensions != null) {
                props.setDimensions(dimensions);
            }
            if (StringUtils.hasText(username)) {
                props.setMilvusUsername(username);
            }
            if (StringUtils.hasText(password)) {
                props.setMilvusPassword(password);
            }
            return props;
        }

        private static String next(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException(name + " 缺少值");
            }
            return args[index];
        }

        private static void printUsage() {
            System.out.println("""
                    预建 / 校验 agent-kit 记忆用的 Milvus collection。

                    用法（账密推荐走环境变量，不进 shell 历史）：
                      export MILVUS_USERNAME=xxx MILVUS_PASSWORD=xxx
                      java -jar agent-kit-tools-0.1.0.jar \\
                          --uri http://localhost:19530 \\
                          --collection myapp_memory \\
                          [--dimensions 1024] [--drop]

                    参数（均可用同名大写环境变量代替：MILVUS_URI / MILVUS_COLLECTION /
                    MILVUS_DIMENSIONS / MILVUS_USERNAME / MILVUS_PASSWORD）：
                      --uri          Milvus gRPC 地址，默认 agent-kit.memory.milvus-uri（http://localhost:19530）
                      --collection   collection 名，默认 agent-kit.memory.collection（ai_agent_memory）。
                                     生产环境务必改成 <你的系统>_memory，并与应用配置一致
                      --dimensions   向量维度，默认 agent-kit.memory.dimensions（1024），
                                     必须与嵌入模型一致（bge-m3 = 1024）
                      --username     Milvus 用户名，开了认证才需要
                      --password     Milvus 密码
                      --drop         已存在但结构不符时先删再建（换嵌入模型/改维度时用；
                                     只删索引，MySQL 正文不动）

                    退出码：0 = 成功或已存在且结构一致；1 = 结构不符被拒绝 / 连接失败。
                    结构定义见 MilvusVectorStore#createCollection()，改字段前先改那边。""");
        }
    }
}
