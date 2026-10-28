-- Run once as the Aurora operator, before restoring data. Never modifies the source database.
\set ON_ERROR_STOP on
SET statement_timeout = '30s';
SET lock_timeout = '3s';
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_database WHERE datname IN ('dictalab_core','dictalab_ai','dictalab_progress'))
       OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname ~ '^dictalab_(core|ai|progress)_(owner|runtime|backup)$') THEN
        RAISE EXCEPTION 'Target databases/roles already exist; inspect before resuming, never overwrite';
    END IF;
END $$;
BEGIN;
-- Aurora IAM login requires INHERIT; the only granted parent is rds_iam.
CREATE ROLE dictalab_core_owner LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE dictalab_core_runtime LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE dictalab_core_backup LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE dictalab_ai_owner LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE dictalab_ai_runtime LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE dictalab_ai_backup LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE dictalab_progress_owner LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE dictalab_progress_runtime LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE dictalab_progress_backup LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT rds_iam TO dictalab_core_owner,dictalab_core_runtime,dictalab_core_backup,
    dictalab_ai_owner,dictalab_ai_runtime,dictalab_ai_backup,
    dictalab_progress_owner,dictalab_progress_runtime,dictalab_progress_backup
    WITH INHERIT TRUE, SET FALSE;
GRANT dictalab_core_owner,dictalab_ai_owner,dictalab_progress_owner TO CURRENT_USER WITH ADMIN OPTION;
COMMIT;
CREATE DATABASE dictalab_core OWNER dictalab_core_owner TEMPLATE template0;
CREATE DATABASE dictalab_ai OWNER dictalab_ai_owner TEMPLATE template0;
CREATE DATABASE dictalab_progress OWNER dictalab_progress_owner TEMPLATE template0;
REVOKE ALL ON DATABASE dictalab_core,dictalab_ai,dictalab_progress FROM PUBLIC;
GRANT CONNECT ON DATABASE dictalab_core TO dictalab_core_runtime,dictalab_core_backup;
GRANT CONNECT ON DATABASE dictalab_ai TO dictalab_ai_runtime,dictalab_ai_backup;
GRANT CONNECT ON DATABASE dictalab_progress TO dictalab_progress_runtime,dictalab_progress_backup;
\connect dictalab_core
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO dictalab_core_runtime,dictalab_core_backup;
\connect dictalab_ai
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO dictalab_ai_runtime,dictalab_ai_backup;
\connect dictalab_progress
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO dictalab_progress_runtime,dictalab_progress_backup;
