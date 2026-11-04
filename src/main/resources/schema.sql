-- MyReview 多用户存储（PostgreSQL）。启动时由 spring.sql.init 执行，全部幂等。
-- 列名与 domain 实体字段 1:1 对应（camelCase → snake_case）；JsonNode 字段一律 text，epoch 毫秒一律 bigint。

CREATE TABLE IF NOT EXISTS users (
    id              varchar(36)  PRIMARY KEY,
    username        varchar(64)  NOT NULL UNIQUE,
    password_hash   varchar(512) NOT NULL,
    nickname        varchar(64),
    avatar_ext      varchar(8),
    -- API Key：AES-256-GCM 密文（主密钥在 E:/m_review/master.key，不入库不入仓库）
    api_key_cipher  text,
    api_key_nonce   text,
    api_key_version int,
    api_key_last4   varchar(8),
    -- 用户级 LLM 配置（null=用服务端默认）
    api_base        text,
    model           text,
    api_format      varchar(16),
    temperature     double precision,
    -- 个人求职总结（feature 11）
    summary_json    text,
    summary_at      bigint,
    created_at      bigint NOT NULL,
    updated_at      bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS stage (
    id           varchar(36) PRIMARY KEY,
    user_id      varchar(36) NOT NULL,
    name         text NOT NULL,
    status       varchar(16),
    summary_json text,
    created_at   bigint NOT NULL,
    updated_at   bigint NOT NULL,
    completed_at bigint
);
CREATE INDEX IF NOT EXISTS idx_stage_user ON stage (user_id, updated_at);

CREATE TABLE IF NOT EXISTS practice (
    id              varchar(36) PRIMARY KEY,
    user_id         varchar(36) NOT NULL,
    stage_id        varchar(36),
    mode            varchar(16),
    topic           text,
    scope_mode      varchar(16),
    scope_text      text,
    level           varchar(16),
    intensity       varchar(16),
    paper_size      int,
    review          boolean NOT NULL DEFAULT false,
    status          varchar(16),
    memory_json     text,
    notes_text      text,
    notes_updated_at bigint,
    digest_json     text,
    created_at      bigint NOT NULL,
    updated_at      bigint NOT NULL,
    completed_at    bigint
);
CREATE INDEX IF NOT EXISTS idx_practice_user_stage ON practice (user_id, stage_id);
CREATE INDEX IF NOT EXISTS idx_practice_stage ON practice (stage_id);

CREATE TABLE IF NOT EXISTS paper (
    id               varchar(36) PRIMARY KEY,
    user_id          varchar(36) NOT NULL,
    practice_id      varchar(36) NOT NULL,
    sequence         int NOT NULL,
    kind             varchar(16),
    focus_points_json text,
    composition_mode varchar(16),
    status           varchar(16),
    summary_json     text,
    progress_json    text,
    plan_json        text,
    max_score        int,
    created_at       bigint NOT NULL,
    submitted_at     bigint
);
CREATE INDEX IF NOT EXISTS idx_paper_user_practice ON paper (user_id, practice_id, sequence);

CREATE TABLE IF NOT EXISTS question (
    id                   varchar(36) PRIMARY KEY,
    user_id              varchar(36) NOT NULL,
    practice_id          varchar(36),
    paper_id             varchar(36) NOT NULL,
    sequence             int NOT NULL,
    prompt               text,
    reference_answer     text,
    knowledge_points_json text,
    demands_json         text,
    assigned_points_json text,
    angle                text,
    tier                 varchar(8),
    question_type        varchar(16),
    points               int,
    revealed_at          bigint,
    created_at           bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_question_user_paper ON question (user_id, paper_id, sequence);
CREATE INDEX IF NOT EXISTS idx_question_user_practice ON question (user_id, practice_id, sequence);

CREATE TABLE IF NOT EXISTS attempt (
    id              varchar(36) PRIMARY KEY,
    user_id         varchar(36) NOT NULL,
    question_id     varchar(36) NOT NULL,
    sequence        int NOT NULL,
    answer          text,
    score           double precision,
    points          double precision,
    feedback        text,
    dimensions_json text,
    submitted_at    bigint NOT NULL,
    evaluated_at    bigint
);
CREATE INDEX IF NOT EXISTS idx_attempt_user_question ON attempt (user_id, question_id, sequence);

CREATE TABLE IF NOT EXISTS follow_up (
    id          varchar(36) PRIMARY KEY,
    user_id     varchar(36) NOT NULL,
    practice_id varchar(36),
    question_id varchar(36) NOT NULL,
    role        varchar(16),
    content     text,
    created_at  bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_followup_user_question ON follow_up (user_id, question_id, created_at);

CREATE TABLE IF NOT EXISTS knowledge_point (
    id           varchar(36) PRIMARY KEY,
    user_id      varchar(36) NOT NULL,
    practice_id  varchar(36) NOT NULL,
    category     varchar(40),
    name         varchar(80),
    source       varchar(16),
    in_scope     boolean NOT NULL DEFAULT true,
    priority     varchar(16),
    evidence_json text,
    bound_quick  text,
    bound_std    text,
    bound_deep   text,
    times_seen   int,
    history_json text,
    angles_json  text,
    mastery      double precision,
    status       varchar(16),
    created_at   bigint NOT NULL,
    updated_at   bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_kp_user_practice ON knowledge_point (user_id, practice_id, created_at);

CREATE TABLE IF NOT EXISTS memory_point (
    id               varchar(36) PRIMARY KEY,
    user_id          varchar(36) NOT NULL,
    practice_id      varchar(36) NOT NULL,
    question_id      varchar(36),
    point_names_json text,
    text             text,
    created_at       bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mp_user_practice ON memory_point (user_id, practice_id, created_at);

CREATE TABLE IF NOT EXISTS mistake (
    id          varchar(36) PRIMARY KEY,
    user_id     varchar(36) NOT NULL,
    practice_id varchar(36) NOT NULL,
    question_id varchar(36) NOT NULL,
    source      varchar(16),
    reason      text,
    score       double precision,
    status      varchar(16),
    resolved_at bigint,
    created_at  bigint NOT NULL,
    updated_at  bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mistake_user_practice ON mistake (user_id, practice_id, created_at);

CREATE TABLE IF NOT EXISTS note (
    id          varchar(36) PRIMARY KEY,
    user_id     varchar(36) NOT NULL,
    practice_id varchar(36) NOT NULL,
    question_id varchar(36),
    scene       varchar(16),
    text        text,
    created_at  bigint NOT NULL,
    updated_at  bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_note_user_practice ON note (user_id, practice_id, created_at);

CREATE TABLE IF NOT EXISTS review_doc (
    id           varchar(36) PRIMARY KEY,
    user_id      varchar(36) NOT NULL,
    practice_id  varchar(36) NOT NULL,
    status       varchar(16),
    content_json text,
    progress_json text,
    created_at   bigint NOT NULL,
    updated_at   bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reviewdoc_user_practice ON review_doc (user_id, practice_id);

-- 网页抓取缓存：全局共享（URL 为键，多用户复用省抓取）
CREATE TABLE IF NOT EXISTS source (
    id         varchar(1024) PRIMARY KEY,
    url        varchar(1024) NOT NULL UNIQUE,
    title      text,
    text       text,
    fetched_at bigint,
    error      text
);

-- 迁移标记等元数据
CREATE TABLE IF NOT EXISTS app_meta (
    key   varchar(64) PRIMARY KEY,
    value text
);

-- 兼容已有表：补用户级 LLM 配置列（PostgreSQL 9.6+ 支持 IF NOT EXISTS）
ALTER TABLE users ADD COLUMN IF NOT EXISTS api_base text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS model text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS api_format varchar(16);
ALTER TABLE users ADD COLUMN IF NOT EXISTS temperature double precision;
-- 求职总结任务状态（持久化，刷新页面/重启后仍能知道是否在生成）
ALTER TABLE users ADD COLUMN IF NOT EXISTS summary_status varchar(16);
ALTER TABLE users ADD COLUMN IF NOT EXISTS summary_error text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS summary_started_at bigint;
