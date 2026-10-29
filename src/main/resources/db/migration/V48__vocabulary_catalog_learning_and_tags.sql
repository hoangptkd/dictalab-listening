-- Expand metadata without changing existing levels, content or review schedules.
ALTER TABLE vocab_decks ALTER COLUMN level TYPE VARCHAR(80);
ALTER TABLE vocab_decks ALTER COLUMN level SET DEFAULT '';
ALTER TABLE vocab_context_exercises ALTER COLUMN level TYPE VARCHAR(80);
ALTER TABLE vocab_decks ADD COLUMN source_deck_id VARCHAR(36) REFERENCES vocab_decks(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX uk_vocab_deck_enrollment ON vocab_decks(owner_user_id,source_deck_id);

CREATE TABLE vocab_deck_tags (
    deck_id VARCHAR(36) NOT NULL REFERENCES vocab_decks(id) ON DELETE CASCADE,
    tag_key VARCHAR(160) NOT NULL,
    tag_name VARCHAR(80) NOT NULL,
    PRIMARY KEY (deck_id,tag_key)
);
CREATE INDEX idx_vocab_deck_tags_key ON vocab_deck_tags(tag_key,deck_id);
