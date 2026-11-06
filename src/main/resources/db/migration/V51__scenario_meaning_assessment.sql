-- Nullable expansion only: historical workflow and learner state are recovered by readers.
ALTER TABLE scenario_drafts ADD COLUMN workflow_snapshot_json TEXT;
ALTER TABLE scenario_activities ADD COLUMN assessment_state_json TEXT;
