"""Receive production configuration over SSH stdin; never log credential values."""
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile
import urllib.parse
import uuid


def pool_environment(size, current):
    if type(size) is not int or size not in (5, 8, 10):
        raise ValueError('Approved pool sizes are 5, 8 and 10')
    updated = current.copy()
    updated.pop('SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE', None)
    updated['DB_POOL_SIZE'] = str(size)
    updated['SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE'] = str(size)
    serialize_environment(updated)
    return updated


def activate_pool(size, current, root, backup_root, group):
    import release
    import urllib.request
    updated = pool_environment(size, current)
    if not backup_root.is_dir() or not release.health():
        raise ValueError('Ready service and protected backup directory are required')
    previous = (root / 'app.env').read_text(encoding='utf-8')
    backup = 'pool-config-' + uuid.uuid4().hex + '.env'
    save(backup_root, group, backup, previous)
    try:
        save(root, group, 'app.env', serialize_environment(updated))
        release.run(['systemctl', 'restart', 'dictalab'])
        release.wait_ready()
        with urllib.request.urlopen('http://127.0.0.1:18183/actuator/prometheus', timeout=5) as response:
            metrics = response.read().decode()
        actual = re.findall(r'^hikaricp_connections_max\{[^\n]*\}\s+([\d.]+)$', metrics, re.M)
        if len(actual) != 1 or float(actual[0]) != size:
            raise RuntimeError('Effective pool differs from requested size')
    except Exception:
        save(root, group, 'app.env', previous)
        release.run(['systemctl', 'restart', 'dictalab'])
        release.wait_ready()
        raise RuntimeError('Pool change failed; previous configuration restored and service ready') from None
    return {'poolSize': size, 'configurationBackup': str(backup_root / backup)}


def support_environment(email, current):
    if not isinstance(email, str) or not re.fullmatch(r'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,63}', email):
        raise ValueError('Invalid public support email')
    updated = current | {'DICTALAB_SUPPORT_EMAIL': email}
    serialize_environment(updated)
    return updated


def redis_environment(url, current):
    if not isinstance(url, str) or any(c in url for c in ('\x00', '\n', '\r')):
        raise ValueError('Invalid Redis configuration')
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != 'rediss' or not parsed.hostname or
            not parsed.password or parsed.port is None or not 1 <= parsed.port <= 65535 or
            parsed.path not in ('', '/', '/0') or parsed.query or parsed.fragment):
        raise ValueError('TLS Redis with an explicit port and database zero is required')
    env = current.copy()
    env['REDIS_URL'] = url
    serialize_environment(env)
    return env

def serialize_environment(env):
    if not isinstance(env, dict):
        raise ValueError('Invalid environment configuration')
    env = env.copy()
    # Underscores delimit nested properties; compound Hikari names must omit hyphens.
    for suffix in ('MAXIMUM_POOL_SIZE', 'MINIMUM_IDLE', 'CONNECTION_TIMEOUT'):
        old = 'SPRING_DATASOURCE_HIKARI_' + suffix
        canonical = 'SPRING_DATASOURCE_HIKARI_' + suffix.replace('_', '')
        if old in env:
            value = env.pop(old)
            if canonical in env and env[canonical] != value:
                raise ValueError('Conflicting pool configuration')
            env[canonical] = value
    if not isinstance(env.get('REDIS_URL'), str) or not env['REDIS_URL'].startswith('rediss://'):
        raise ValueError('TLS Redis is required')
    for name in ('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SIGNING_KEY'):
        if not isinstance(env.get(name), str) or not env[name]:
            raise ValueError('Required cloud configuration is missing')
    lines = []
    for name, value in sorted(env.items()):
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name):
            raise ValueError('Invalid environment key')
        if not isinstance(value, str) or any(c in value for c in ('\x00', '\n', '\r')):
            raise ValueError('Environment values must be single-line strings')
        escaped = value.replace('\\', '\\\\').replace('"', '\\"')
        lines.append(name + '="' + escaped + '"')
    return '\n'.join(lines) + '\n'


def save(root, group, name, content):
    # Replace atomically; never follow an existing destination symlink or truncate a live secret file.
    fd, temporary = tempfile.mkstemp(prefix='.' + name + '.', dir=root)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(temporary, 0o640)
        os.chown(temporary, 0, group)
        os.replace(temporary, root / name)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def production_environment(source, current):
    # Preserve the cloud connection and Linux paths while restoring the source feature flags.
    protected = ('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'PORT', 'MANAGEMENT_PORT',
                 'HTTP_THREADS', 'HTTP_MAX_CONNECTIONS', 'DICTALAB_MEDIA_ROOT',
                 'DICTALAB_PERSONAL_CONTENT_ROOT', 'DICTALAB_PERSONAL_CONTENT_MANIFEST',
                 'BACKUP_SUCCESS_MARKER', 'AWS_SHARED_CREDENTIALS_FILE',
                 'DICTALAB_PUBLIC_URL', 'DICTALAB_ALLOWED_ORIGINS', 'DICTALAB_TURN_URL',
                 'SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE',
                 'SPRING_DATASOURCE_HIKARI_MINIMUMIDLE',
                 'SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT')
    env = source.copy()
    for name in protected:
        if name in current:
            env[name] = current[name]
    for suffix in ('MAXIMUM_POOL_SIZE', 'MINIMUM_IDLE', 'CONNECTION_TIMEOUT'):
        env.pop('SPRING_DATASOURCE_HIKARI_' + suffix, None)
    for name in ('JWT_SIGNING_KEY', 'REDIS_URL'):
        if source.get(name) != current.get(name):
            raise ValueError('Source and target authentication configuration differ')
    env['DICTALAB_LOG_FILE'] = '/var/lib/dictalab/logs/dictalab.json'
    env['DICTALAB_AI_QUEUE_ENABLED'] = source.get('DICTALAB_AI_QUEUE_ENABLED', 'false')
    env['DICTALAB_MEDIA_S3_CLEANUP_ENABLED'] = source.get('DICTALAB_MEDIA_S3_CLEANUP_ENABLED', 'false')
    serialize_environment(env)
    return env


def main():
    import grp
    root = pathlib.Path('/etc/dictalab')
    if sys.argv[1:] in (['--rehearsal-existing'], ['--check-redis'], ['--production-existing'], ['--redis-existing'], ['--support-existing'], ['--pool-existing']):
        env = {}
        for line in (root / 'app.env').read_text(encoding='utf-8').splitlines():
            if line and not line.startswith('#'):
                name, value = line.split('=', 1)
                env[name] = shlex.split(value)[0]
        if sys.argv[1:] == ['--pool-existing']:
            result = activate_pool(json.load(sys.stdin).get('pool_size'), env, root,
                                   pathlib.Path('/var/lib/dictalab/backups'), grp.getgrnam('dictalab').gr_gid)
            print(json.dumps(result))
            return
        if sys.argv[1:] == ['--support-existing']:
            updated = support_environment(json.load(sys.stdin).get('email'), env)
            group = grp.getgrnam('dictalab').gr_gid
            backup_root = pathlib.Path('/var/lib/dictalab/backups')
            if not backup_root.is_dir():
                raise ValueError('Protected backup directory is required')
            save(backup_root, group, 'support-config-' + uuid.uuid4().hex + '.env',
                 (root / 'app.env').read_text(encoding='utf-8'))
            save(root, group, 'app.env', serialize_environment(updated))
            print('PUBLIC_SUPPORT_CONFIGURED_RESTART_REQUIRED')
            return
        if sys.argv[1:] == ['--redis-existing']:
            updated = redis_environment(json.load(sys.stdin).get('redis_url'), env)
            state = subprocess.run(['systemctl', 'show', 'dictalab.service', '--property=ActiveState', '--value'],
                                   capture_output=True, text=True, check=True, timeout=15)
            if state.stdout.strip() != 'inactive':
                raise ValueError('Stop the application before changing its Redis store')
            backup_root = pathlib.Path('/var/lib/dictalab/backups')
            if not backup_root.is_dir():
                raise ValueError('Protected backup directory is required')
            group = grp.getgrnam('dictalab').gr_gid
            backup_name = 'redis-config-' + uuid.uuid4().hex + '.env'
            save(backup_root, group, backup_name, (root / 'app.env').read_text(encoding='utf-8'))
            save(root, group, 'app.env', serialize_environment(updated))
            print('REDIS_CONFIGURATION_BACKUP=' + str(backup_root / backup_name))
            print('REDIS_CONFIGURATION_REPLACED_RESTART_REQUIRED')
            return
        if sys.argv[1:] == ['--check-redis']:
            result = subprocess.run(['java', '-Xmx32m', '-cp', '/opt/dictalab/tools', 'RedisIsolationCheck', '--ping'],
                                    env=os.environ | {'REDIS_URL': env['REDIS_URL']},
                                    capture_output=True, text=True, timeout=20)
            if result.returncode or result.stdout.strip() != 'REDIS_READONLY_PING=OK':
                raise ValueError('Redis readiness check failed')
            print('REDIS_READONLY_PING=OK')
            return
        if sys.argv[1:] == ['--production-existing']:
            source = json.load(sys.stdin)
            env = production_environment(source['environment'], env)
            payload = {'environment': env, 'aws': source['aws']}
        else:
            for suffix in ('MAXIMUM_POOL_SIZE', 'MINIMUM_IDLE', 'CONNECTION_TIMEOUT'):
                env.pop('SPRING_DATASOURCE_HIKARI_' + suffix, None)
            env.update(SHOPAIKEY_API_KEY='', CARTESIA_API_KEY='',
                       DICTALAB_COACH_ENABLED='false', DICTALAB_CONVERSATION_ENABLED='false',
                       DICTALAB_AI_QUEUE_ENABLED='false', DICTALAB_LISTENING_QUEUE_ENABLED='false',
                       DICTALAB_MEDIA_S3_CLEANUP_ENABLED='false',
                       SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE='5',
                       SPRING_DATASOURCE_HIKARI_MINIMUMIDLE='0',
                       SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT='3000',
                       LISTENING_DISPATCH_MS='86400000')
            # Direct sessions retain SET deadlines and Flyway's session-level lock semantics.
            env['DB_URL'] = env['DB_URL'].replace('-pooler.', '.')
            payload = {'environment': env, 'aws': (root / 'aws-credentials').read_text(encoding='utf-8')}
    elif sys.argv[1:]:
        raise ValueError('Unsupported configuration arguments')
    else:
        payload = json.load(sys.stdin)
    # Validate everything before changing either configuration file.
    content = serialize_environment(payload.get('environment'))
    aws = payload.get('aws')
    if not isinstance(aws, str) or not aws.strip() or '\x00' in aws:
        raise ValueError('Invalid AWS profile configuration')
    root.mkdir(mode=0o750, exist_ok=True)
    group = grp.getgrnam('dictalab').gr_gid
    os.chmod(root, 0o750)
    os.chown(root, 0, group)
    save(root, group, 'aws-credentials', aws)
    save(root, group, 'app.env', content)
    print('CLOUD_CONFIGURATION_PROTECTED')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError):
        # Never include input values in validation errors.
        raise SystemExit('Invalid cloud configuration; no credential values logged') from None
