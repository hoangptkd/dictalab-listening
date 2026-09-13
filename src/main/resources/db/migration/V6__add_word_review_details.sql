ALTER TABLE word_attempts ADD COLUMN first_answer VARCHAR(200);
ALTER TABLE word_attempts ADD COLUMN reveal_type VARCHAR(20);

ALTER TABLE word_attempts
    ADD CONSTRAINT ck_word_reveal_type
        CHECK (reveal_type IS NULL OR reveal_type IN ('REVEAL_WORD', 'REVEAL_SENTENCE'));
