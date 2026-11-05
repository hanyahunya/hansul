CREATE TABLE recipe_snapshot (
    id UUID PRIMARY KEY,
    source_provider VARCHAR(40) NOT NULL,
    source_recipe_id VARCHAR(40) NOT NULL,
    source_url VARCHAR(500) NOT NULL,
    source_format VARCHAR(30) NOT NULL,
    source_evidence_locator VARCHAR(500) NOT NULL,
    parser_version VARCHAR(30) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    title VARCHAR(500) NOT NULL,
    base_servings NUMERIC(10, 3),
    base_servings_text VARCHAR(200),
    description TEXT,
    total_time_text VARCHAR(100),
    primary_image_url TEXT,
    raw_structured_data TEXT,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_recipe_snapshot_source_hash
        UNIQUE (source_provider, source_recipe_id, content_hash)
);

CREATE INDEX ix_recipe_snapshot_source
    ON recipe_snapshot (source_provider, source_recipe_id, imported_at DESC);

CREATE TABLE recipe_ingredient (
    id BIGSERIAL PRIMARY KEY,
    recipe_id UUID NOT NULL REFERENCES recipe_snapshot(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    raw_text TEXT NOT NULL,
    name VARCHAR(500) NOT NULL,
    quantity_text VARCHAR(300),
    normalized_amount NUMERIC(18, 6),
    normalized_unit VARCHAR(100),
    normalization_warning VARCHAR(300),
    source_type VARCHAR(30) NOT NULL,
    source_locator VARCHAR(500) NOT NULL,
    CONSTRAINT uq_recipe_ingredient_position UNIQUE (recipe_id, position)
);

CREATE TABLE recipe_step (
    id BIGSERIAL PRIMARY KEY,
    recipe_id UUID NOT NULL REFERENCES recipe_snapshot(id) ON DELETE CASCADE,
    step_number INTEGER NOT NULL,
    raw_text TEXT NOT NULL,
    normalized_text TEXT NOT NULL,
    image_url TEXT,
    source_type VARCHAR(30) NOT NULL,
    source_locator VARCHAR(500) NOT NULL,
    CONSTRAINT uq_recipe_step_number UNIQUE (recipe_id, step_number)
);

CREATE TABLE recipe_warning (
    recipe_id UUID NOT NULL REFERENCES recipe_snapshot(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    warning TEXT NOT NULL,
    PRIMARY KEY (recipe_id, position)
);
