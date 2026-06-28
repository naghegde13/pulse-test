-- V20: Chat sessions and messages for GenAI interface

CREATE TABLE chat_sessions (
    id              VARCHAR(26) PRIMARY KEY,
    tenant_id       VARCHAR(26) NOT NULL REFERENCES tenants(id),
    pipeline_id     VARCHAR(26) REFERENCES pipelines(id),
    user_id         VARCHAR(26) NOT NULL REFERENCES users(id),
    title           VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_chat_session_tenant ON chat_sessions(tenant_id);
CREATE INDEX idx_chat_session_pipeline ON chat_sessions(pipeline_id);

CREATE TABLE chat_messages (
    id              VARCHAR(26) PRIMARY KEY,
    session_id      VARCHAR(26) NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT NOT NULL,
    tool_calls      JSONB,
    tool_results    JSONB,
    plan_id         VARCHAR(26) REFERENCES plans(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_chat_msg_session ON chat_messages(session_id);
