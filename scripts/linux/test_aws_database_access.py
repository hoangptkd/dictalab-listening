"""Check the IAM template's privilege boundaries without cloud credentials."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DatabaseAccessTest(unittest.TestCase):
    def test_operations_policy_only_controls_the_existing_cluster(self):
        policy = json.loads((ROOT / 'ops/aws/aurora-operations-policy.json').read_text(encoding='utf-8'))
        self.assertEqual([{'Effect': 'Allow',
                          'Action': ['rds:DescribeDBClusters', 'rds:StartDBCluster', 'rds:StopDBCluster'],
                          'Resource': 'arn:aws:rds:ap-southeast-1:042407443847:cluster:dictalab-aurora-demo'}],
                         policy['Statement'])

    @unittest.skipUnless(os.environ.get('TEST_AWS_DATABASE_BOOTSTRAP') == '1', 'Opt-in isolated PostgreSQL 17')
    def test_bootstrap_isolation_and_existing_target_rejection(self):
        name = 'dictalab-aws-bootstrap-' + secrets.token_hex(4)
        def execute(args, **kwargs):
            return subprocess.run(args, capture_output=True, timeout=45, **kwargs)
        started = execute(['docker', 'run', '--detach', '--network', 'none', '--name', name,
            '--env', 'POSTGRES_PASSWORD', 'postgres:17-alpine'],
            env=os.environ | {'POSTGRES_PASSWORD': secrets.token_urlsafe(32)})
        self.assertEqual(0, started.returncode)
        try:
            for _ in range(40):
                if execute(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres']).returncode == 0:
                    break
                time.sleep(.25)
            else:
                self.fail('PostgreSQL did not accept TCP connections within 10 seconds')
            def sql(statement):
                return execute(['docker', 'exec', '-i', '--user', 'postgres', name,
                    'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', 'postgres'], input=statement.encode('utf-8'))
            self.assertEqual(0, sql('CREATE ROLE rds_iam NOLOGIN; CREATE TABLE source_marker(id int); INSERT INTO source_marker VALUES (123);').returncode)
            bootstrap = (ROOT / 'ops/aws/databases.sql').read_text(encoding='utf-8')
            first = sql(bootstrap)
            self.assertEqual(0, first.returncode, first.stderr.decode())
            self.assertNotEqual(0, sql(bootstrap).returncode)
            for service in ('core', 'ai', 'progress'):
                role = 'dictalab_' + service + '_runtime'
                self.assertEqual(b't', sql(f"SELECT rolinherit FROM pg_roles WHERE rolname='{role}';").stdout.strip())
                self.assertEqual(b't|f', sql(f"SELECT pg_has_role('{role}','rds_iam','USAGE'),"
                    f"pg_has_role('{role}','rds_iam','SET');").stdout.strip())
                for target in ('core', 'ai', 'progress'):
                    allowed = sql(f"SELECT has_database_privilege('{role}','dictalab_{target}','CONNECT');")
                    self.assertEqual(b't' if service == target else b'f', allowed.stdout.strip())
                owner = 'dictalab_' + service + '_owner'
                self.assertEqual(b'f|f|f|f', sql(f"SELECT rolsuper,rolcreatedb,rolcreaterole,rolbypassrls FROM pg_roles WHERE rolname='{owner}';").stdout.strip())
            self.assertEqual(b'123', sql('SELECT id FROM source_marker;').stdout.strip())
            for service in ('core', 'ai', 'progress'):
                tables = ('ai_provider_pools', 'ai_provider_accounts',
                          'ai_registry_schema' if service == 'ai' else 'flyway_schema_history', 'business_data')
                fixture = f'\\connect dictalab_{service}\nSET ROLE dictalab_{service}_owner;\n'
                fixture += '\n'.join(f'CREATE TABLE {table}(id SERIAL PRIMARY KEY);' for table in tables)
                self.assertEqual(0, sql(fixture).returncode)
            grants = (ROOT / 'ops/aws/grants.sql').read_text(encoding='utf-8')
            applied = sql(grants)
            self.assertEqual(0, applied.returncode, applied.stderr.decode())
            for service in ('core', 'ai', 'progress'):
                prefix = f'\\connect dictalab_{service}\n'
                runtime, backup = f'dictalab_{service}_runtime', f'dictalab_{service}_backup'
                checks = sql(prefix + f"SELECT has_table_privilege('{runtime}','business_data','INSERT'),"
                    f"has_table_privilege('{backup}','business_data','SELECT'),"
                    f"has_table_privilege('{backup}','business_data','UPDATE');")
                self.assertEqual(b'f|t|f' if service == 'ai' else b't|t|f', checks.stdout.strip())
                self.assertEqual(b'f', sql(prefix + f"SELECT has_schema_privilege('{runtime}','public','CREATE');").stdout.strip())
                if service != 'ai':
                    self.assertEqual(b't|f', sql(prefix + f"SELECT has_table_privilege('{runtime}','flyway_schema_history','SELECT'),"
                        f"has_table_privilege('{runtime}','flyway_schema_history','UPDATE');").stdout.strip())
                self.assertEqual(b'f' if service == 'core' else b't',
                    sql(prefix + f"SELECT has_table_privilege('{runtime}','ai_provider_accounts','SELECT');").stdout.strip())
                self.assertEqual(0, sql(prefix + f'SET ROLE dictalab_{service}_owner; CREATE TABLE future_data(id int);').returncode)
                self.assertEqual(b't|f', sql(prefix + f"SELECT has_table_privilege('{backup}','future_data','SELECT'),"
                    f"has_table_privilege('{runtime}','future_data','INSERT');").stdout.strip())
            self.assertEqual(b'123', sql('SELECT id FROM source_marker;').stdout.strip())
        finally:
            self.assertEqual(0, execute(['docker', 'rm', '--force', name]).returncode)

    def test_exact_database_logins_without_cloud_or_secret_read_permissions(self):
        template = json.loads((ROOT / 'ops/aws/database-access.json').read_text(encoding='utf-8'))
        resources = template['Resources']
        expected = {'AiUser': ['ai_runtime'], 'ProgressUser': ['progress_runtime'],
                    'MigrationUser': ['core_owner', 'ai_owner', 'progress_owner'],
                    'BackupUser': ['core_backup', 'ai_backup', 'progress_backup']}
        for name, roles in expected.items():
            properties = resources[name]['Properties']
            self.assertNotIn('LoginProfile', properties)
            policy = properties['Policies'][0]['PolicyDocument']
            self.assertEqual(1, len(policy['Statement']))
            statement = policy['Statement'][0]
            self.assertEqual(['rds-db:connect'], statement['Action'])
            self.assertEqual('Allow', statement['Effect'])
            self.assertEqual(roles, [x['Fn::Sub'].split('/dictalab_')[1] for x in statement['Resource']])
            self.assertTrue(all('*' not in x['Fn::Sub'] and '${ClusterResourceId}' in x['Fn::Sub']
                                for x in statement['Resource']))
            self.assertTrue(all(':dbuser:${ClusterResourceId}/dictalab_' in x['Fn::Sub']
                                for x in statement['Resource']))
        core = resources['CoreDatabasePolicy']['Properties']['PolicyDocument']['Statement'][0]
        self.assertEqual(['rds-db:connect'], core['Action'])
        self.assertEqual(1, len(core['Resource']))
        self.assertTrue(core['Resource'][0]['Fn::Sub'].endswith('/dictalab_core_runtime'))
        self.assertIn(':dbuser:${ClusterResourceId}/', core['Resource'][0]['Fn::Sub'])
        self.assertEqual(4, sum(x['Type'] == 'AWS::IAM::AccessKey' for x in resources.values()))
        self.assertEqual({'OperatorCredentialSecretArn'}, set(template['Outputs']))
        self.assertEqual('Retain', resources['OperatorCredentialStore']['DeletionPolicy'])


if __name__ == '__main__':
    unittest.main()
