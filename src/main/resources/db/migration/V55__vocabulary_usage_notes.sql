-- Usage notes are separate from examples, tags and learning schedules.
ALTER TABLE vocab_notes ADD COLUMN usage_notes VARCHAR(1000) NOT NULL DEFAULT '';
