-- Additive: historical scores retain version 1; only newly completed work uses version 2.
ALTER TABLE practice_sessions ADD COLUMN scoring_version INTEGER NOT NULL DEFAULT 1;
