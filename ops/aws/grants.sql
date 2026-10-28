-- Run as the Aurora operator after restores and owner-run migrations complete.
-- Reapply after schema changes; runtime permissions are deliberately explicit.
\set ON_ERROR_STOP on
\connect dictalab_core
BEGIN;
SET LOCAL ROLE dictalab_core_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, dictalab_core_runtime, dictalab_core_backup;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, dictalab_core_runtime, dictalab_core_backup;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO dictalab_core_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO dictalab_core_runtime;
REVOKE ALL ON ai_provider_pools, ai_provider_accounts, flyway_schema_history FROM dictalab_core_runtime;
GRANT SELECT ON flyway_schema_history TO dictalab_core_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO dictalab_core_backup;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO dictalab_core_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE dictalab_core_owner IN SCHEMA public GRANT SELECT ON TABLES TO dictalab_core_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE dictalab_core_owner IN SCHEMA public GRANT SELECT ON SEQUENCES TO dictalab_core_backup;
COMMIT;
\connect dictalab_ai
BEGIN;
SET LOCAL ROLE dictalab_ai_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, dictalab_ai_runtime, dictalab_ai_backup;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, dictalab_ai_runtime, dictalab_ai_backup;
GRANT SELECT ON ai_provider_pools, ai_provider_accounts, ai_registry_schema TO dictalab_ai_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO dictalab_ai_backup;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO dictalab_ai_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE dictalab_ai_owner IN SCHEMA public GRANT SELECT ON TABLES TO dictalab_ai_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE dictalab_ai_owner IN SCHEMA public GRANT SELECT ON SEQUENCES TO dictalab_ai_backup;
COMMIT;
\connect dictalab_progress
BEGIN;
SET LOCAL ROLE dictalab_progress_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, dictalab_progress_runtime, dictalab_progress_backup;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, dictalab_progress_runtime, dictalab_progress_backup;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO dictalab_progress_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO dictalab_progress_runtime;
REVOKE ALL ON flyway_schema_history FROM dictalab_progress_runtime;
GRANT SELECT ON flyway_schema_history TO dictalab_progress_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO dictalab_progress_backup;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO dictalab_progress_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE dictalab_progress_owner IN SCHEMA public GRANT SELECT ON TABLES TO dictalab_progress_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE dictalab_progress_owner IN SCHEMA public GRANT SELECT ON SEQUENCES TO dictalab_progress_backup;
COMMIT;
