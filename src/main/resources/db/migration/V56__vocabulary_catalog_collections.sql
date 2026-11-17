-- Source catalog structure is data-driven; schedules remain in vocab_cards.
CREATE TABLE vocab_catalog_nodes (
    id VARCHAR(80) PRIMARY KEY,
    parent_id VARCHAR(80) REFERENCES vocab_catalog_nodes(id),
    collection_id VARCHAR(80) NOT NULL,
    deck_id VARCHAR(36) UNIQUE REFERENCES vocab_decks(id),
    title VARCHAR(200) NOT NULL,
    sort_order INTEGER NOT NULL,
    level_label VARCHAR(80) NOT NULL DEFAULT '',
    baseline_revision BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_vocab_catalog_parent ON vocab_catalog_nodes(parent_id,sort_order,id);
CREATE INDEX idx_vocab_catalog_collection ON vocab_catalog_nodes(collection_id);
CREATE TABLE vocab_catalog_items (
    collection_id VARCHAR(80) NOT NULL REFERENCES vocab_catalog_nodes(id),
    source_key VARCHAR(64) NOT NULL,
    note_id VARCHAR(36) NOT NULL UNIQUE REFERENCES vocab_notes(id),
    node_id VARCHAR(80) NOT NULL REFERENCES vocab_catalog_nodes(id),
    subtopic VARCHAR(200) NOT NULL,
    classification_json TEXT NOT NULL,
    source_json TEXT NOT NULL,
    import_note_json TEXT NOT NULL,
    baseline_revision BIGINT NOT NULL DEFAULT 0,
    admin_override BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(collection_id,source_key)
);
CREATE INDEX idx_vocab_catalog_item_node ON vocab_catalog_items(node_id);
CREATE TABLE vocab_catalog_versions (
    collection_id VARCHAR(80) NOT NULL REFERENCES vocab_catalog_nodes(id),
    version_hash VARCHAR(64) NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    bundle_json TEXT NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(collection_id,version_hash)
);
