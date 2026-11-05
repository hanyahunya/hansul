ALTER TABLE recipe_snapshot ADD COLUMN owner_subject VARCHAR(255);
ALTER TABLE recipe_snapshot ADD COLUMN video_provider VARCHAR(20);
ALTER TABLE recipe_snapshot ADD COLUMN video_id VARCHAR(100);
ALTER TABLE recipe_snapshot ADD COLUMN video_page_url VARCHAR(500);
ALTER TABLE recipe_snapshot ADD COLUMN video_duration_seconds INTEGER;
ALTER TABLE recipe_snapshot ADD COLUMN video_channel VARCHAR(300);
ALTER TABLE recipe_snapshot ADD COLUMN video_embeddable BOOLEAN;
CREATE INDEX ix_recipe_snapshot_video_cache
    ON recipe_snapshot (source_provider, source_recipe_id, parser_version, imported_at DESC)
    WHERE owner_subject IS NULL;

ALTER TABLE recipe_step ADD COLUMN video_start_seconds INTEGER;
ALTER TABLE recipe_step ADD COLUMN video_end_seconds INTEGER;
ALTER TABLE recipe_step ADD COLUMN key_frame_seconds INTEGER;

CREATE TABLE video_upload (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    duration_seconds INTEGER,
    chunk_size_bytes INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    recipe_id UUID REFERENCES recipe_snapshot(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX uq_video_upload_active_hash
    ON video_upload (owner_subject, sha256) WHERE status IN ('RECEIVING', 'COMPLETE');
CREATE INDEX ix_video_upload_owner_status ON video_upload (owner_subject, status);
CREATE INDEX ix_video_upload_expiry ON video_upload (status, expires_at);

CREATE TABLE recipe_import_job (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(255) NOT NULL,
    source_kind VARCHAR(20) NOT NULL,
    source_provider VARCHAR(40) NOT NULL,
    source_ref VARCHAR(100) NOT NULL,
    source_url VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    stage VARCHAR(30) NOT NULL,
    cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    recipe_id UUID REFERENCES recipe_snapshot(id) ON DELETE SET NULL,
    error_code VARCHAR(60),
    error_message VARCHAR(500),
    attempts INTEGER NOT NULL DEFAULT 0,
    input_tokens BIGINT,
    output_tokens BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX uq_recipe_import_job_in_flight
    ON recipe_import_job (source_provider, source_ref) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_recipe_import_job_owner_created ON recipe_import_job (owner_subject, created_at DESC);
CREATE INDEX ix_recipe_import_job_status ON recipe_import_job (status, created_at);
