"""Run: python -m unittest discover -s scripts/linux -p 'test_*.py'. No live secrets."""
import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('configure_cloud', pathlib.Path(__file__).with_name('configure-cloud.py'))
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


class CloudConfigurationTest(unittest.TestCase):
    def env(self):
        return {'DB_URL': 'jdbc:postgresql://example/test', 'DB_USERNAME': 'test',
                'DB_PASSWORD': 'test-only', 'JWT_SIGNING_KEY': 'test-only',
                'REDIS_URL': 'rediss://example:6379/'}

    def test_quotes_backslashes_and_dollar_signs_are_preserved(self):
        env = self.env()
        env['EXTRA'] = 'a"b\\c$d% foo'
        self.assertIn('EXTRA="a\\"b\\\\c$d% foo"\n', config.serialize_environment(env))

    def test_support_changes_only_the_public_contact(self):
        original = self.env()
        self.assertEqual(original | {'DICTALAB_SUPPORT_EMAIL': 'owner@example.com'},
                         config.support_environment('owner@example.com', original))
        self.assertNotIn('DICTALAB_SUPPORT_EMAIL', original)
        for invalid in (None, '', 'owner', 'a@b', 'a@example.com\nEXTRA=value'):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                config.support_environment(invalid, original)

    def test_pool_changes_only_both_effective_size_keys(self):
        original = self.env() | {'SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE': '5', 'DB_POOL_SIZE': '5',
                                 'SPRING_DATASOURCE_HIKARI_MINIMUMIDLE': '0'}
        for size in (5, 8, 10):
            self.assertEqual(original | {'SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE': str(size), 'DB_POOL_SIZE': str(size)},
                             config.pool_environment(size, original))
        self.assertEqual('5', original['DB_POOL_SIZE'])
        for invalid in (None, True, '8', 0, 11, 100):
            with self.subTest(size=invalid), self.assertRaises(ValueError):
                config.pool_environment(invalid, original)

    def test_pool_mismatch_restores_configuration_and_restarts(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(config.os, 'chown', create=True), \
             patch('release.health', return_value=True), patch('release.run') as run, patch('release.wait_ready'), \
             patch('urllib.request.urlopen') as request:
            root = pathlib.Path(temporary)
            backup = root / 'backups'; backup.mkdir()
            previous = config.serialize_environment(self.env() | {'DB_POOL_SIZE': '5'})
            (root / 'app.env').write_text(previous, encoding='utf-8')
            request.return_value.__enter__.return_value.read.return_value = b'hikaricp_connections_max{pool="HikariPool-1"} 5.0\n'
            with self.assertRaisesRegex(RuntimeError, 'previous configuration restored'):
                config.activate_pool(8, self.env(), root, backup, 123)
            self.assertEqual(previous, (root / 'app.env').read_text(encoding='utf-8'))
            self.assertEqual(previous, next(backup.iterdir()).read_text(encoding='utf-8'))
            self.assertEqual(2, run.call_count)

    def test_pool_activation_checks_effective_size_and_preserves_backup(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(config.os, 'chown', create=True), \
             patch('release.health', return_value=True), patch('release.run'), patch('release.wait_ready'), \
             patch('urllib.request.urlopen') as request:
            root = pathlib.Path(temporary)
            backup = root / 'backups'; backup.mkdir()
            previous = config.serialize_environment(self.env())
            (root / 'app.env').write_text(previous, encoding='utf-8')
            request.return_value.__enter__.return_value.read.return_value = b'hikaricp_connections_max{pool="HikariPool-1"} 8.0\n'
            result = config.activate_pool(8, self.env(), root, backup, 123)
            self.assertIn('DB_POOL_SIZE="8"', (root / 'app.env').read_text(encoding='utf-8'))
            self.assertEqual(previous, pathlib.Path(result['configurationBackup']).read_text(encoding='utf-8'))

    def test_multiline_null_and_non_string_values_are_rejected(self):
        for value in ('a\nb', 'a\rb', 'a\x00b', None, False):
            with self.subTest(value=value):
                env = self.env()
                env['EXTRA'] = value
                with self.assertRaises(ValueError):
                    config.serialize_environment(env)

    def test_invalid_keys_are_rejected(self):
        for key in ('', '1KEY', 'BAD-KEY', 'KEY=value', 'Việt'):
            with self.subTest(key=key):
                env = self.env()
                env[key] = 'test'
                with self.assertRaises(ValueError):
                    config.serialize_environment(env)

    def test_required_credentials_are_rejected_if_missing(self):
        for name in ('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SIGNING_KEY'):
            env = self.env()
            del env[name]
            with self.assertRaises(ValueError):
                config.serialize_environment(env)

    def test_plaintext_redis_is_rejected(self):
        env = self.env()
        env['REDIS_URL'] = 'redis://example:6379/'
        with self.assertRaises(ValueError):
            config.serialize_environment(env)

    def test_redis_replacement_changes_only_url_without_mutating_input(self):
        current = self.env() | {'DICTALAB_AI_QUEUE_ENABLED': 'false', 'EXTRA': 'preserve'}
        replacement = 'rediss://default:test-only@example.com:6379/0'
        updated = config.redis_environment(replacement, current)
        self.assertEqual(current | {'REDIS_URL': replacement}, updated)
        self.assertEqual('rediss://example:6379/', current['REDIS_URL'])
        self.assertEqual(current['JWT_SIGNING_KEY'], updated['JWT_SIGNING_KEY'])

    def test_redis_replacement_rejects_unsafe_or_ambiguous_urls(self):
        urls = [None, '', 'redis://default:test@example.com:6379/0',
                'rediss://default:test@example.com/0', 'rediss://example.com:6379/0',
                'rediss://default:test@example.com:6379/1',
                'rediss://default:test@example.com:6379/0?x=1',
                'rediss://default:test@example.com:6379/0#fragment',
                'rediss://default:test@example.com:99999/0',
                'rediss://default:test@example.com:6379/0\nEXTRA=bad']
        for url in urls:
            with self.subTest(url=url), self.assertRaises(ValueError):
                config.redis_environment(url, self.env())

    def test_legacy_pool_names_are_normalized_without_mutating_input(self):
        env = self.env()
        for suffix, value in [('MAXIMUM_POOL_SIZE', '5'), ('MINIMUM_IDLE', '0'), ('CONNECTION_TIMEOUT', '3000')]:
            env['SPRING_DATASOURCE_HIKARI_' + suffix] = value
        content = config.serialize_environment(env)
        for suffix, value in [('MAXIMUMPOOLSIZE', '5'), ('MINIMUMIDLE', '0'), ('CONNECTIONTIMEOUT', '3000')]:
            self.assertIn('SPRING_DATASOURCE_HIKARI_' + suffix + '="' + value + '"\n', content)
        self.assertNotIn('SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=', content)
        self.assertIn('SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT', env)

    def test_conflicting_pool_configuration_is_rejected(self):
        env = self.env()
        env['SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT'] = '3000'
        env['SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT'] = '5000'
        with self.assertRaises(ValueError):
            config.serialize_environment(env)

    def test_production_restores_provider_flags_but_preserves_cloud_and_linux_settings(self):
        source = self.env() | {'DB_PASSWORD': 'old-test-only', 'DB_URL': 'jdbc:postgresql://old/test',
                              'DICTALAB_COACH_ENABLED': 'true', 'GEMINI_API_KEY': 'provider-test-only',
                              'SHOPAIKEY_API_KEY': 'retired-test-only',
                              'DICTALAB_AI_FALLBACK_API_KEY': 'retired-fallback-test-only',
                              'DICTALAB_LOG_FILE': 'C:\\old\\app.log', 'LISTENING_DISPATCH_MS': '1000',
                              'SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT': '9999'}
        current = self.env() | {'DB_URL': 'jdbc:postgresql://cloud/test',
                               'SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT': '3000',
                               'DICTALAB_MEDIA_ROOT': '/var/lib/dictalab/media',
                               'LISTENING_DISPATCH_MS': '86400000'}
        env = config.production_environment(source, current)
        self.assertEqual('jdbc:postgresql://cloud/test', env['DB_URL'])
        self.assertEqual('test-only', env['DB_PASSWORD'])
        self.assertEqual('true', env['DICTALAB_COACH_ENABLED'])
        self.assertEqual('provider-test-only', env['GEMINI_API_KEY'])
        self.assertNotIn('SHOPAIKEY_API_KEY', env)
        self.assertNotIn('DICTALAB_AI_FALLBACK_API_KEY', env)
        self.assertEqual('1000', env['LISTENING_DISPATCH_MS'])
        self.assertEqual('/var/lib/dictalab/logs/dictalab.json', env['DICTALAB_LOG_FILE'])
        self.assertNotIn('SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT', env)
        self.assertEqual('false', env['DICTALAB_AI_QUEUE_ENABLED'])
        self.assertEqual('false', env['DICTALAB_MEDIA_S3_CLEANUP_ENABLED'])

    def test_cutover_rejects_changed_shared_authentication_credentials(self):
        for name in ('REDIS_URL', 'JWT_SIGNING_KEY'):
            with self.subTest(name=name):
                source = self.env()
                source[name] = 'changed-test-only'
                with self.assertRaises(ValueError):
                    config.production_environment(source, self.env())

    def test_production_keeps_s3_identity_and_all_audio_mode(self):
        storage = {'AWS_PROFILE': 'dictalab-audio', 'DICTALAB_MEDIA_S3_ENABLED': 'true',
                   'DICTALAB_MEDIA_S3_BUCKET': 'current-bucket', 'DICTALAB_MEDIA_S3_REGION': 'us-east-1',
                   'DICTALAB_MEDIA_S3_PREFIX': 'dictalab/audio/',
                   'DICTALAB_MEDIA_S3_SIGNED_URL_DURATION': '30m',
                   'DICTALAB_MEDIA_S3_ALL_AUDIO': 'true', 'DICTALAB_MEDIA_S3_PROXY_AUDIO': 'true'}
        source = self.env() | {'DICTALAB_MEDIA_S3_ENABLED': 'false',
                              'DICTALAB_MEDIA_S3_BUCKET': 'old-bucket',
                              'DICTALAB_MEDIA_S3_PREFIX': 'wrong/'}
        env = config.production_environment(source, self.env() | storage)
        for key, value in storage.items():
            self.assertEqual(value, env[key])

    def test_atomic_write_and_permissions(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(config.os, 'chown', create=True) as chown:
            root = pathlib.Path(temporary)
            config.save(root, 123, 'app.env', 'test-only\n')
            self.assertEqual('test-only\n', (root / 'app.env').read_text(encoding='utf-8'))
            chown.assert_called_once()
            self.assertEqual((0, 123), chown.call_args.args[1:])
            self.assertEqual(['app.env'], sorted(p.name for p in root.iterdir()))

    def test_failed_replace_preserves_old_file_and_removes_temporary(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(config.os, 'chown', create=True):
            root = pathlib.Path(temporary)
            config.save(root, 123, 'app.env', 'old-test-only')
            with patch.object(config.os, 'replace', side_effect=OSError('injected')):
                with self.assertRaises(OSError):
                    config.save(root, 123, 'app.env', 'new-test-only')
            self.assertEqual('old-test-only', (root / 'app.env').read_text())
            self.assertEqual(['app.env'], sorted(p.name for p in root.iterdir()))


if __name__ == '__main__':
    unittest.main()
