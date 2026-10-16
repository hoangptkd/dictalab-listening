-- Expand accepted values; all existing scenarios remain valid. No learning data is rewritten.
-- Replacing/validating the CHECK takes a table lock: release preflight must check table size and lock waits.
ALTER TABLE ai_conversation_sessions DROP CONSTRAINT ck_conversation_shape;
ALTER TABLE ai_conversation_sessions ADD CONSTRAINT ck_conversation_shape CHECK (
    scenario IN ('cafe','restaurant','wrong-order','ingredients','directions','tickets','change-trip','taxi',
                 'product','clothes','returns','delivery','assignment','meeting','requirements','group-project',
                 'hotel','appointment','repair','opening-hours','introductions','plans','invitation','weekend')
    AND difficulty IN ('A1','A2','B1') AND version>=0);
