\set ON_ERROR_STOP on
SET statement_timeout = '15s';
SET lock_timeout = '3s';
SET TIME ZONE 'UTC';
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT format(
  'SELECT json_build_object(''table'', %L, ''rows'', count(*), ''sha256'', encode(sha256(convert_to(coalesce(string_agg(row_hash, '''' ORDER BY row_hash), ''''), ''UTF8'')), ''hex'')) FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text, ''UTF8'')), ''hex'') row_hash FROM %I.%I t) hashed;',
  tablename, schemaname, tablename)
FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
\gexec
SELECT json_build_object('schemaVersion', (SELECT max(installed_rank) FROM flyway_schema_history WHERE success),
  'unvalidatedConstraints', (SELECT count(*) FROM pg_constraint WHERE NOT convalidated AND connamespace = 'public'::regnamespace));
COMMIT;
