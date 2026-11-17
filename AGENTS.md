# agent-kit 开发约定(AI 编码工具必读)

本文件适用于任何在本仓库开发与审查的 AI 编码工具(Claude Code、workbuddy、Codex 等)。
`CLAUDE.md` 与 `AGENTS.md` 内容必须保持一致,改其中一个必须同步改另一个。

---

## 硬约束(违反即返工)

### 1. 一切扩展点参考 Dubbo / Java SPI 的设计:可插拔、可自定义实现

整个脚手架的设计必须遵循 **Dubbo / Java SPI 机制的设计思想**:每个扩展点都是「接口 + 契约」,
实现可插拔、可替换、可自定义,框架只面向接口编程,绝不写死具体实现。

具体要求:

- **新扩展点 = 接口 + 注册收集 + 启动期装配**。新增任何扩展点时,必须同时给出:
  1. 一个稳定的接口(契约),定义在 `agent-kit-core`,不依赖任何具体实现;
  2. 一个注册/收集机制(当前约定:Spring 容器收集 `List<接口>` 或 `ObjectProvider<接口>`,
     参照 `ModelProviderRegistry` 收集 `List<ModelProvider>` 的写法);
  3. 至少一个内置默认实现,「不实现时也能跑」——每个扩展点都要在文档里写清缺省行为
     (格式参照 `docs/01-架构设计.md` §5 扩展点一览表)。
- **不写死**:运行时代码只依赖接口,不 `instanceof` 具体实现、不硬编码实现类名。
  需要区分行为时,给接口加 `id()` / `engineName()` 这类标识方法,按标识与配置路由,
  参照 `ChatEngine` 双引擎(legacy / agentscope)与 `ModelProviderRegistry#byId` 的做法。
- **可降级**:扩展点没有实现时必须零开销退化(埋点无 exporter 时退化 noop 的
  `AiTracing` 是标准范例),而不是抛异常拒绝启动;「缺实现就崩」只允许出现在
  启动期校验(参照 `EngineGuard`:配置与模块不配对时,启动即失败并打印人话报错),
  不允许出现在运行期。
- **依赖代价随实现走**:重依赖(如 AgentScope、Milvus 客户端、OTel SDK)只能装在实现
  自己的独立模块里(参照 `agent-kit-agentscope`、`agent-kit-memory`、examples/tools 的
  profile 隔离),通过 Maven optional / profile / 独立坐标隔离,不许进 `agent-kit-core`
  与 `agent-kit-starter`。
- **契约优先于实现**:改接口(加方法、改语义)前先确认所有既有实现的升级成本;
  给既有接口加行为一律加 `default` 方法(`ChatSink#onError` 是范例),不允许让既有
  实现编译失败。`AgentResult` 语义与 `ChatSink` 四帧是冻结契约,改动需在架构文档
  说明理由并同步更新 `docs/01-架构设计.md`。
- **自定义实现零框架改动**:使用者接入新渠道/新引擎/新工具时,框架源码一行不改
  (`endpoints` 配一段就能长出自定义渠道,是验收基准)。做任何设计决策前先问:
  这个需求能否通过「新实现 + 配置」满足?只有确实不能时才允许改框架代码,并说明原因。

> 设计参照:Dubbo 的「微内核 + 插件」(内核只管装配与生命周期,扩展点全部外置)与
> Java SPI 的「接口在提供方,实现在使用方」;落地机制是 Spring 容器(Bean 收集代替
> `META-INF/services` 文件)。
