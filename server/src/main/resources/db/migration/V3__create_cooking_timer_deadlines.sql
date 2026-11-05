ALTER TABLE cooking_session
    ALTER COLUMN target_servings TYPE NUMERIC;

CREATE TABLE cooking_timer_deadline (
    session_id UUID NOT NULL REFERENCES cooking_session(id) ON DELETE CASCADE,
    timer_cue_id VARCHAR(140) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id, timer_cue_id)
);
