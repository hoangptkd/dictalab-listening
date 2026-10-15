-- The existing pass row is the durable outbox: PENDING commits with the answers/retry.
-- Nullable metadata only; no rewrite/backfill of historical grading results.
ALTER TABLE listening_passes ADD COLUMN grading_publish_after TIMESTAMP WITH TIME ZONE;
