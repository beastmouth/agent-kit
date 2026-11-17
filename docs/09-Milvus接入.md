# 09 · Milvus 接入与运维

给「要打开长期记忆」的人。记忆的机制（四类两态、检索与提炼）在 [03-会话与记忆](03-会话与记忆.md)，
配置项全表在 [07-配置参考](07-配置参考.md)。这里只讲三件事：**外部设施怎么起、应用怎么接、出问题怎么查**。

---

## 0. Milvus 在脚手架里的角色

一句话：**Milvus 只是一份可重建的向量索引，不是事实源**。

| | MySQL `ai_agent_memory` | Milvus collection |
|---|---|---|
| 存什么 | 记忆正文、类型、状态、归属（事实源） | `(id, namespace, owner, vector)` 四个字段（可重建索引） |
| 谁写 | `AgentMemoryService`（提炼 / 手动维护） | 同上，写正文时同步 upsert 向量 |
| 丢了会怎样 | 数据丢了 = 记忆真丢了 | 按 MySQL 全量灌回即恢复（`rebuildFromMysql()`） |

由「可重建」这个定位推出四条行为，都是刻意的：

- **collection 不用提前建**。首次用到记忆功能时按配置自动创建（字段与索引见下）；
- **应用启动不连 Milvus**。连接是惰性的，「服务起来了没报错」不代表「Milvus 通了」，验证见 §4；
- **Milvus 挂了不影响对话**。检索降级为空列表，提炼留日志等下一轮；
- **collection 结构可以随时推倒**。结构与代码不一致时自动 drop 重建，再按 MySQL 灌回。

collection 结构（`dimensions` 建库后不可改，换嵌入模型 = 重建索引，见 §6）：

```text
id         INT64          主键 = MySQL 主键
namespace  VARCHAR(128)   分区，检索必带
owner      VARCHAR(128)   归属，共享记忆写空串（向量库标量字段不支持 null）
vector     FLOAT_VECTOR   维度 = agent-kit.memory.dimensions
索引        AUTOINDEX / COSINE
```

嵌入模型是另一条独立依赖（OpenAI 兼容 `/v1/embeddings`，默认本地 Ollama 的 bge-m3），
与 Milvus 缺一不可，见 §2。

---

## 1. 起 Milvus（standalone，单容器）

开发与小规模生产用 standalone 即可：单容器内嵌 etcd 与对象存储，暴露 gRPC `19530`、HTTP `9091`。

```bash
curl -sfL https://raw.githubusercontent.com/milvus-io/milvus/master/scripts/standalone_embed.sh -o standalone_embed.sh
bash standalone_embed.sh start     # 拉起 milvus-standalone 容器，数据落在脚本同目录 ./volumes
```

验证（9091 是健康检查端口，返回 OK 才算起来）：

```bash
curl http://localhost:9091/healthz     # → OK
```

常用操作：`bash standalone_embed.sh stop`（停）/ `delete`（停并删数据卷）。

三点提醒：

1. **版本**：脚本默认拉最新镜像。脚手架 SDK 是 2.5.14，server 用 `milvusdb/milvus:v2.5.14`
   对齐最稳。要配 Web 管理界面 Attu 的话**版本必须配对**（`milvusdb/milvus:v2.5.14` ↔
   `zilliz/attu:v2.5.10`；Attu v3.0 明确不支持 Milvus 2.5.x，升 Milvus 要连 Attu 一起升）。
2. **认证默认关闭**。公网或共享环境部署时在 milvus.yaml 开
   `common.security.authorizationEnabled: true`，先改默认管理员（root / Milvus）的密码，
   再给应用配 `milvus-username` / `milvus-password`。**开了认证但不配用户名**，应用匿名建连
   会在建连阶段被拒（`UNAUTHENTICATED`），检索永远空列表——发生在第一次用到记忆时，不是启动期。
3. **数据目录不要进仓库**。数据卷（几百 MB 起）与含真实密码的 milvus.yaml 都放仓库外的运行目录；
   仓库里只放「怎么把容器跑起来」的定义文件。密码用环境变量注入，任何提交到仓库的文件只放占位符。

---

## 2. 起嵌入模型

默认指向本地 Ollama 的 bge-m3（1024 维）：

```bash
ollama pull bge-m3

# 验证：返回 JSON 里 embedding 数组长度应为 1024
curl http://localhost:11434/v1/embeddings -H 'Content-Type: application/json' \
  -d '{"model":"bge-m3","input":"连通性测试"}'
```

换云端嵌入（SiliconFlow、OpenAI 等都是同一套协议）只改四个配置：
`base-url`（含 `/v1`）、`api-key`、`model`、`dimensions`（跟着新模型走）。

维度必须在配置里与模型一致——嵌入客户端每次都会校验返回维度，不一致直接抛错（检索降级为空）；
而 Milvus collection 是按 `dimensions` 建的，**中途改维度要重建索引**（见 §6），不是改配置就完事。

---

## 3. 应用侧接入

### 依赖

引 `agent-kit-memory` 即可，`milvus-sdk-java` 是它的传递依赖，不用单独加：

```xml
<dependency>
    <groupId>com.beastmouth.kit</groupId>
    <artifactId>agent-kit-memory</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 配置

```yaml
agent-kit:
  memory:
    enabled: true
    collection: yourapp_memory              # ← 必填，由你自己给出，不要用默认名（理由见下）
    base-url: http://localhost:11434/v1     # 嵌入服务（OpenAI 兼容，含 /v1）
    model: bge-m3
    dimensions: 1024                        # 必须与嵌入模型一致
    milvus-uri: http://localhost:19530
    # milvus-username: root                 # Milvus 开了认证才填，见 §1
    # milvus-password: ${MILVUS_PASSWORD}
    top-k: 5
    min-score: 0.35
```

**`collection` 是可配置项，名字由你自己给出**——脚手架不替你起名。代码里的默认 `ai_agent_memory`
只是让 demo 能跑的占位：它是通用名，同一台 Milvus 上跑两套 agent 会互相串记忆（而且
`rebuildFromMysql()` 会把你的表灌进共用集合）。打开记忆之前先决定名字，建议
`<你的系统>_memory`（如 `family_memory`）。名字定下后保持三处一致：
**应用配置（`agent-kit.memory.collection`）、预建工具的 `--collection`、Milvus 里实际存在的 collection**。

账密等敏感值走环境变量注入（`${MILVUS_PASSWORD}` 这种写法），**不要写进提交到仓库的任何文件**——
配置模板里只放占位符，真实值进部署环境的环境变量或 secret 管理。

全部配置项（提炼渠道、规则槽位、记忆上限等）见 [07-配置参考](07-配置参考.md) 的 `agent-kit.memory.*` 一节。

### 预建 collection（可选，运维动作）

应用首启会自动建 collection，通常不需要提前建。要「DDL 先于发版」时用运维工具
（`agent-kit-tools` 模块，与 `MilvusVectorStore#createCollection()` 共用同一套 SDK 与结构定义）：

```bash
# 构建工具（tools profile，默认构建不含）
mvn -Ptools clean install -DskipTests

# 账密走环境变量，不进 shell 历史与流水线日志
export MILVUS_USERNAME=root MILVUS_PASSWORD=${MILVUS_PASSWORD}

java -cp agent-kit-tools/target/classes:agent-kit-memory/target/classes:agent-kit-core/target/classes:\
$(mvn -o -q -pl agent-kit-tools dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
    com.beastmouth.agentkit.tools.milvus.MilvusCollectionTool \
    --uri http://your-milvus-host:19530 --collection yourapp_memory
```

`--collection` 填**你自己决定的名字**，必须与 §3 应用配置里的 `agent-kit.memory.collection` 一致。
参数均可用同名大写环境变量代替（`MILVUS_URI` / `MILVUS_COLLECTION` / `MILVUS_DIMENSIONS` /
`MILVUS_USERNAME` / `MILVUS_PASSWORD`）；`--help` 看全表。行为：

- collection 不存在 → 按与应用完全一致的结构创建（幂等，重复执行无害）；
- 已存在且结构一致 → 打印 `[跳过]` 直接退出 0，流水线可直接当闸门用；
- 已存在但结构不符 → **拒绝并退出 1**（不替人做删除决定），确认后显式加 `--drop` 重建
  （只删索引，MySQL 正文不动，重建后由应用按 MySQL 灌回）。

---

## 4. 验证接通

连接与建 collection 都发生在**第一次调用记忆功能**时（`recall` / 提炼 / `addSharedMemory`），
所以验证动作就是触发一次：

```java
memoryService.recall("my-app", "u-001", "连通性测试");   // 返回空列表也算通
```

日志出现这两条即为接通：

```text
MilvusVectorStore#connected uri=http://localhost:19530 collection=yourapp_memory
MilvusVectorStore#createCollection#已创建 name=yourapp_memory dim=1024 attempt=1
```

（第二条只有首次有；collection 已存在时跳过。）

两侧数据自查：

- MySQL `ai_agent_memory`：有没有 `ACTIVE` 行——没有正文就没有可检索的东西，先聊几轮让它记；
- Milvus 侧：记一条之后 collection 行数应随之 +1（用 Attu 看最直观，见 §1 的版本配对）。

---

## 5. 故障排查

记忆故障**全部表现为降级**（检索空列表 + warn 日志），不会让对话报错——所以排查入口是日志，不是接口报错。

| 现象（看日志） | 原因 | 修法 |
|---|---|---|
| `AgentMemoryService#recall#降级为空 ... err=` 含连接/超时 | Milvus 没起、地址错、19530 未暴露 | 先 `curl :9091/healthz` 验 Milvus，再核 `milvus-uri` |
| err 含 `UNAUTHENTICATED` | Milvus 开了认证但应用没配用户名密码 | 配 `milvus-username` / `milvus-password`（§1） |
| `嵌入请求失败` / `嵌入服务返回 4xx` | 嵌入服务没起、模型名错、`base-url` 少 `/v1` | 用 §2 的 curl 验证嵌入服务 |
| `嵌入维度不符：期望 X 实际 Y` | `dimensions` 与模型输出不一致 | 改 `dimensions`；中途换维度还要重建索引（§6） |
| `旧结构缺少 namespace / owner 字段，已删除待重建` | 同名 collection 是老版本脚手架建的 | 正常自愈，无需干预：drop 后自动按 MySQL 灌回 |
| 检索一直空、无任何报错 | 链路都通，但没有记忆过 `min-score`，或本来就没记过 | 调低 `min-score` 试；查 `ai_agent_memory` 有没有 ACTIVE 行 |
| 检索命中别的系统的记忆 | 与同实例其他系统共用默认 collection 名 | 改名 + 重建（§6） |

---

## 6. 运维动作

### 重建索引

collection 误删、Milvus 重装、换 collection 名之后，按 MySQL 全量灌回：

```java
int rebuilt = memoryService.rebuildFromMysql();   // 返回重建条数
```

正常情况下不需要手动调：结构过期时 `recall` 内部会自动触发一次重建
（`ensureReady()` → `takeLegacyResetSignal()` → `rebuildFromMysql()`）。

### 换嵌入模型（或改维度）

collection 的向量维度建库后不可改，步骤是：

1. 改配置 `model` / `dimensions`（必要时连 `base-url` / `api-key`）；
2. **换一个新的 collection 名**（名字仍由你自己给出，建议带版本或日期后缀如
   `yourapp_memory_v2`，或手动 drop 旧 collection）；
3. 调 `rebuildFromMysql()` 按 MySQL 重新向量化灌入。

跳过第 2 步的表现：检索一直空，或日志报维度不符——旧 collection 还是老维度。

### 清理

- MySQL 里 `SUPERSEDED` 行保留「以前是什么口径」的痕迹，不物理删；要清理按 `status` + 时间归档；
- Milvus 侧无需单独清理：废弃记忆的向量在 UPDATE/DROP 时已同步删除；
  全量清空直接 drop collection 再 `rebuildFromMysql()`。

---

## 7. 与其他文档的分工

| 想了解 | 去哪 |
|---|---|
| 记忆怎么提炼、怎么注入提示词、namespace/owner 语义 | [03-会话与记忆](03-会话与记忆.md) |
| `agent-kit.memory.*` 每一项的含义与默认值 | [07-配置参考](07-配置参考.md) |
| 上线前自查（collection 改名等） | [06-接入清单](06-接入清单.md) §10 |

## 8. 接入前的三个自查

1. **`collection` 是你自己给的名字吗？** 不是默认的 `ai_agent_memory`、也不是抄文档示例的
   `yourapp_memory`，而是按你的系统起的（如 `<你的系统>_memory`）；
2. **三处一致吗？** 应用配置、预建工具的 `--collection`、Milvus 里实际存在的 collection 是同一个名字；
3. **账密没落仓库吧？** 配置文件里只有 `${MILVUS_PASSWORD}` 占位，真实值在部署环境的环境变量里。
