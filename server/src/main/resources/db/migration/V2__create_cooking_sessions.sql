CREATE TABLE cooking_session (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(255) NOT NULL,
    recipe_id UUID NOT NULL REFERENCES recipe_snapshot(id),
    target_servings NUMERIC(10, 3) NOT NULL CHECK (target_servings > 0),
    plan_json TEXT NOT NULL,
    plan_source VARCHAR(40) NOT NULL,
    plan_warnings_json TEXT NOT NULL,
    state_json TEXT NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_cooking_session_owner_updated
    ON cooking_session (owner_subject, updated_at DESC);

CREATE TABLE cooking_command_result (
    session_id UUID NOT NULL REFERENCES cooking_session(id) ON DELETE CASCADE,
    command_key VARCHAR(140) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id, command_key)
);

CREATE TABLE cooking_session_event (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES cooking_session(id) ON DELETE CASCADE,
    state_version BIGINT NOT NULL,
    command_key VARCHAR(140) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(140) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_cooking_session_event_order
    ON cooking_session_event (session_id, id);
