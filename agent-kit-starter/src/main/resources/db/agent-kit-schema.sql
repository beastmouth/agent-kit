-- =============================================================================
-- agent-kit 建表脚本（MySQL 8.0+）
--
-- 全部是「脚手架自己的表」，与业务表没有任何外键关系：
--   · 观测三表   ai_run / ai_llm_call / ai_tool_call   纯日志，可按时间分区或定期清理
--   · 评测三表   ai_eval_score / ai_feedback / ai_golden_case
--   · 价目表     ai_price                              成本核算的唯一价格来源
--   · 会话三表   ai_agent_session / ai_agent_message / ai_agent_memory（memory 模块）
--
-- 归属语义只有两个不透明字符串：
--   namespace  分区键（租户 / 组织 / 项目 / 机器人实例，业务自定义）
--   owner      归属方（用户 id / 工单号 / 设备号）；NULL 表示分区内共享
-- 脚手架不解析它们，也不建任何用户表。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 一次任务一行：一个用户请求 = 一个 run = 一条 trace
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_run (
    id               BIGINT       NOT NULL COMMENT '主键（雪花）',
    trace_id         VARCHAR(64)           DEFAULT NULL,
    subject_id       VARCHAR(128)          DEFAULT NULL COMMENT '调用方标识，原样透传',
    labels           VARCHAR(1024)         DEFAULT NULL COMMENT '调用方自定义标签 JSON',
    session_id       BIGINT                DEFAULT NULL,
    channel          VARCHAR(32)           DEFAULT NULL COMMENT 'WEB / IM / API / GOLDEN',
    agent_name       VARCHAR(64)           DEFAULT NULL,
    entry_type       VARCHAR(32)           DEFAULT NULL,
    input_hash       CHAR(64)              DEFAULT NULL COMMENT '输入 sha256，用于去重与复现',
    input_preview    VARCHAR(512)          DEFAULT NULL,
    output_preview   VARCHAR(1024)         DEFAULT NULL,
    status           VARCHAR(32)           DEFAULT NULL COMMENT 'RUNNING / SUCCESS / FAIL',
    error_code       VARCHAR(64)           DEFAULT NULL,
    step_count       INT                   DEFAULT 0,
    llm_call_count   INT                   DEFAULT 0,
    tool_call_count  INT                   DEFAULT 0,
    input_tokens     INT                   DEFAULT 0,
    output_tokens    INT                   DEFAULT 0,
    cached_tokens    INT                   DEFAULT 0,
    reasoning_tokens INT                   DEFAULT 0,
    cost_cny         DECIMAL(12, 6)        DEFAULT 0,
    ttft_ms          INT                   DEFAULT 0,
    duration_ms      INT                   DEFAULT 0,
    fallback_used    TINYINT               DEFAULT 0,
    parse_fail       TINYINT               DEFAULT 0,
    fallback_reply   TINYINT               DEFAULT 0,
    need_clarify     TINYINT               DEFAULT 0,
    empty_result     TINYINT               DEFAULT 0,
    task_success     TINYINT               DEFAULT NULL COMMENT '1 成功 0 失败 NULL 未判定',
    quality_label    VARCHAR(32)           DEFAULT NULL,
    created_at       DATETIME(3)           DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_run_created (created_at),
    KEY idx_run_session (session_id),
    KEY idx_run_subject (subject_id, created_at),
    KEY idx_run_trace (trace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 任务（一次请求一行）';

-- -----------------------------------------------------------------------------
-- 一次模型调用一行：成本与延迟的明细来源
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_llm_call (
    id               BIGINT       NOT NULL,
    run_id           BIGINT                DEFAULT NULL,
    trace_id         VARCHAR(64)           DEFAULT NULL,
    step_seq         INT                   DEFAULT 0 COMMENT 'run 内第几步',
    provider         VARCHAR(32)           DEFAULT NULL,
    model            VARCHAR(96)           DEFAULT NULL,
    purpose          VARCHAR(32)           DEFAULT NULL COMMENT 'ROUTE / ACT / SUMMARIZE / JUDGE …',
    stream           TINYINT               DEFAULT 0,
    input_tokens     INT                   DEFAULT 0,
    output_tokens    INT                   DEFAULT 0,
    cached_tokens    INT                   DEFAULT 0,
    reasoning_tokens INT                   DEFAULT 0,
    usage_estimated  TINYINT               DEFAULT 0 COMMENT '1 表示 usage 是估算值',
    cost_cny         DECIMAL(12, 6)        DEFAULT 0,
    ttft_ms          INT                   DEFAULT 0,
    duration_ms      INT                   DEFAULT 0,
    request_chars    INT                   DEFAULT 0,
    response_chars   INT                   DEFAULT 0,
    finish_reason    VARCHAR(32)           DEFAULT NULL,
    status           VARCHAR(32)           DEFAULT NULL,
    error_code       VARCHAR(64)           DEFAULT NULL,
    created_at       DATETIME(3)           DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_llm_run (run_id),
    KEY idx_llm_created (created_at),
    KEY idx_llm_provider_model (provider, model, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 模型调用明细';

-- -----------------------------------------------------------------------------
-- 一次工具执行一行：「工具返回 0 条却给了结论」这类病的唯一凭据
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_tool_call (
    id          BIGINT      NOT NULL,
    run_id      BIGINT               DEFAULT NULL,
    trace_id    VARCHAR(64)          DEFAULT NULL,
    step_seq    INT                  DEFAULT 0,
    tool_name   VARCHAR(96)          DEFAULT NULL,
    args_json   VARCHAR(2048)        DEFAULT NULL,
    status      VARCHAR(32)          DEFAULT NULL COMMENT 'OK / FAIL / NOT_FOUND',
    error_code  VARCHAR(64)          DEFAULT NULL,
    result_rows INT                  DEFAULT NULL COMMENT 'NULL 表示工具没报告行数',
    duration_ms INT                  DEFAULT 0,
    created_at  DATETIME(3)          DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_tool_run (run_id),
    KEY idx_tool_name (tool_name, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 工具调用明细';

-- -----------------------------------------------------------------------------
-- 评测分数：规则层、评审层、黄金集都写这张表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_eval_score (
    id             BIGINT        NOT NULL,
    run_id         BIGINT                 DEFAULT NULL,
    trace_id       VARCHAR(64)            DEFAULT NULL,
    eval_set       VARCHAR(32)            DEFAULT NULL COMMENT 'ONLINE / GOLDEN',
    metric         VARCHAR(64)            DEFAULT NULL,
    score          DECIMAL(6, 4)          DEFAULT NULL,
    label          VARCHAR(64)            DEFAULT NULL,
    judge_model    VARCHAR(96)            DEFAULT NULL,
    rubric_version VARCHAR(16)            DEFAULT NULL,
    detail_json    VARCHAR(2048)          DEFAULT NULL,
    batch_id       VARCHAR(64)            DEFAULT NULL COMMENT '黄金集批次',
    created_at     DATETIME(3)            DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_score_run (run_id),
    KEY idx_score_metric (metric, created_at),
    KEY idx_score_batch (batch_id, metric)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 质量评测分数';

-- -----------------------------------------------------------------------------
-- 显式反馈：同一任务、同一调用方只保留一条
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_feedback (
    id         BIGINT      NOT NULL,
    run_id     BIGINT               DEFAULT NULL,
    subject_id VARCHAR(128)         DEFAULT NULL,
    rating     VARCHAR(16)          DEFAULT NULL COMMENT 'UP / DOWN / NEUTRAL',
    tags       VARCHAR(128)         DEFAULT NULL,
    comment    VARCHAR(255)         DEFAULT NULL,
    created_at DATETIME(3)          DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_subject (run_id, subject_id),
    KEY idx_feedback_rating (rating, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 显式反馈（点赞/点踩）';

-- -----------------------------------------------------------------------------
-- 黄金集：离线回归用的固定用例
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_golden_case (
    id             BIGINT      NOT NULL,
    name           VARCHAR(128)         DEFAULT NULL,
    layer          VARCHAR(32)          DEFAULT NULL COMMENT '基础 / 组合 / 边界 …',
    input          VARCHAR(2048)        DEFAULT NULL,
    expected_tool  VARCHAR(255)         DEFAULT NULL COMMENT '逗号分隔；COMPOUND 场景表示都要',
    expected_rows  VARCHAR(64)          DEFAULT NULL,
    expect_clarify TINYINT              DEFAULT 0,
    enabled        TINYINT              DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_golden_enabled (enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 黄金集用例';

-- -----------------------------------------------------------------------------
-- 价目：成本核算只认这张表，代码里不出现价格常量
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_price (
    id               BIGINT        NOT NULL,
    provider         VARCHAR(32)            DEFAULT NULL,
    model            VARCHAR(96)            DEFAULT NULL,
    currency         VARCHAR(8)             DEFAULT 'CNY',
    cache_hit_per_m  DECIMAL(12, 6)         DEFAULT 0 COMMENT '每百万 token，缓存命中价',
    cache_miss_per_m DECIMAL(12, 6)         DEFAULT 0 COMMENT '每百万 token，未命中价',
    output_per_m     DECIMAL(12, 6)         DEFAULT 0 COMMENT '每百万 token，输出价',
    peak_multiplier  DECIMAL(6, 3)          DEFAULT 1 COMMENT '高峰倍率',
    peak_windows     VARCHAR(128)           DEFAULT NULL COMMENT '高峰时段，如 08:30-00:30',
    effective_from   DATE                   DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_price_model (provider, model, effective_from)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='模型价目（按生效日期取最新）';

-- -----------------------------------------------------------------------------
-- 会话：namespace + owner 双维度隔离
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_agent_session (
    id              BIGINT      NOT NULL COMMENT '主键（雪花）',
    namespace       VARCHAR(128)         DEFAULT NULL,
    owner           VARCHAR(128)         DEFAULT NULL,
    title           VARCHAR(64)          DEFAULT NULL,
    channel         VARCHAR(32)          DEFAULT 'WEB',
    source_id       VARCHAR(128)         DEFAULT NULL COMMENT '渠道内来源标识（如 IM open_id）',
    agent_name      VARCHAR(64)          DEFAULT NULL COMMENT '最近一次回答的子 Agent',
    message_count   INT                  DEFAULT 0,
    last_message_at DATETIME(3)          DEFAULT NULL,
    create_time     DATETIME(3)          DEFAULT NULL,
    update_time     DATETIME(3)          DEFAULT NULL,
    deleted         TINYINT              DEFAULT 0,
    version         INT                  DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_session_scope (namespace, owner, deleted, last_message_at),
    KEY idx_session_source (namespace, owner, channel, source_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 会话';

CREATE TABLE IF NOT EXISTS ai_agent_message (
    id          BIGINT      NOT NULL,
    session_id  BIGINT               DEFAULT NULL,
    namespace   VARCHAR(128)         DEFAULT NULL,
    owner       VARCHAR(128)         DEFAULT NULL,
    role        VARCHAR(16)          DEFAULT NULL COMMENT 'USER / ASSISTANT',
    agent_name  VARCHAR(64)          DEFAULT NULL,
    content     MEDIUMTEXT,
    tool_events MEDIUMTEXT           DEFAULT NULL COMMENT '本轮工具动作 JSON',
    create_time DATETIME(3)          DEFAULT NULL,
    update_time DATETIME(3)          DEFAULT NULL,
    deleted     TINYINT              DEFAULT 0,
    version     INT                  DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_message_session (session_id, deleted, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 会话消息（只存用户可见内容）';

-- -----------------------------------------------------------------------------
-- 长期记忆：正文是事实源，向量在 Milvus（可随时按本表重建）
-- owner IS NULL = 分区内共享
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_agent_memory (
    id                BIGINT       NOT NULL,
    namespace         VARCHAR(128)          DEFAULT NULL,
    owner             VARCHAR(128)          DEFAULT NULL COMMENT 'NULL 表示分区内共享',
    kind              VARCHAR(32)           DEFAULT NULL COMMENT 'RULE / PREFERENCE / FACT / HABIT',
    content           VARCHAR(1024)         DEFAULT NULL,
    source_session_id BIGINT                DEFAULT NULL,
    status            VARCHAR(32)           DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUPERSEDED',
    create_time       DATETIME(3)           DEFAULT NULL,
    update_time       DATETIME(3)           DEFAULT NULL,
    deleted           TINYINT               DEFAULT 0,
    version           INT                   DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_memory_scope (namespace, owner, status, deleted),
    KEY idx_memory_status (status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 长期记忆';
