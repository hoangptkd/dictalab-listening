-- Explicit operator upgrade. Existing account identity and encrypted keys are untouched.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $$
DECLARE legacy_name text;
DECLARE matches integer;
BEGIN
    SELECT count(*), min(conname::text) INTO matches, legacy_name
    FROM pg_constraint
    WHERE conrelid = 'ai_provider_pools'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%platform%'
      AND pg_get_constraintdef(oid) LIKE '%''gemini''%'
      AND pg_get_constraintdef(oid) LIKE '%''deepseek''%'
      AND pg_get_constraintdef(oid) LIKE '%''openai_compatible''%';
    IF matches <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one legacy provider ID constraint';
    END IF;
    ALTER TABLE ai_provider_pools ADD CONSTRAINT ck_ai_provider_id
        CHECK (platform ~ '^[a-z][a-z0-9_]{0,31}$');
    EXECUTE format('ALTER TABLE ai_provider_pools DROP CONSTRAINT %I', legacy_name);
END $$;
