"""Provision only DictaLab's loopback broker; enable learning separately after a verified release.

Run as root with --prepare, --install, --enable or --disable. Credentials never leave app.env.
Package repository and signing fingerprint follow https://www.rabbitmq.com/docs/install-debian.
"""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import secrets
import shlex
import shutil
import subprocess
import tempfile
import urllib.request

ENV = pathlib.Path('/etc/dictalab/app.env')
FINGERPRINT = '0A9AF2115F4687BD29803A206B73A36E6026DFCA'


def run(args, timeout=180, data=None):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Broker operation failed: ' + args[0] + '; output suppressed')
    return result.stdout.decode()


def settings():
    return dict((line.split('=', 1)[0], shlex.split(line.split('=', 1)[1])[0])
                for line in ENV.read_text().splitlines() if line and not line.startswith('#'))


def change_env(changes):
    os.umask(0o077)
    backup = pathlib.Path(tempfile.mkdtemp(prefix='learning-env-', dir='/var/lib/dictalab/backups'))
    shutil.copy2(ENV, backup / 'app.env')
    lines = [line for line in ENV.read_text().splitlines() if line.split('=', 1)[0] not in changes]
    lines += [name + '=' + shlex.quote(str(value)) for name, value in changes.items()]
    pending = ENV.with_name('app.env.learning-pending')
    with pending.open('x') as stream:
        stream.write('\n'.join(lines) + '\n'); stream.flush(); os.fsync(stream.fileno())
    os.chmod(pending, 0o600)
    os.replace(pending, ENV)
    print(json.dumps({'configurationBackup': str(backup)}))


def prepare():
    if 'VERSION_CODENAME=noble' not in pathlib.Path('/etc/os-release').read_text() or run(['dpkg', '--print-architecture']).strip() != 'amd64':
        raise RuntimeError('This provisioner is reviewed only for Ubuntu Noble amd64')
    key = pathlib.Path('/usr/share/keyrings/com.rabbitmq.team.gpg')
    if not key.exists():
        with urllib.request.urlopen('https://keys.openpgp.org/vks/v1/by-fingerprint/' + FINGERPRINT, timeout=30) as response:
            armored = response.read(200000)
        details = run(['gpg', '--batch', '--show-keys', '--with-colons'], data=armored)
        if FINGERPRINT not in details:
            raise RuntimeError('Repository signing fingerprint mismatch')
        key.write_bytes(subprocess.run(['gpg', '--batch', '--dearmor'], input=armored, capture_output=True, check=True).stdout)
        os.chmod(key, 0o644)
    repo = pathlib.Path('/etc/apt/sources.list.d/dictalab-rabbitmq.list')
    contents = ''.join('deb [arch=amd64 signed-by=' + str(key) + '] https://deb' + str(mirror)
                       + '.rabbitmq.com/' + package + '/ubuntu/noble noble main\n'
                       for package in ('rabbitmq-erlang', 'rabbitmq-server') for mirror in (1, 2))
    if repo.exists() and repo.read_text() != contents:
        raise RuntimeError('Unexpected existing repository; inspect before replacing')
    repo.write_text(contents)
    run(['apt-get', 'update'], timeout=240)
    print(run(['apt-cache', 'policy', 'rabbitmq-server', 'erlang-base']))


def install():
    if pathlib.Path('/etc/rabbitmq/rabbitmq.conf').exists():
        raise RuntimeError('Existing RabbitMQ config: inspect before provisioning')
    versions = run(['apt-cache', 'madison', 'rabbitmq-server'])
    if '4.3.6-1' not in versions:
        raise RuntimeError('Verified RabbitMQ 4.3.6 package unavailable; no untested substitution')
    pathlib.Path('/etc/apt/preferences.d/dictalab-erlang').write_text('Package: erlang*\nPin: version 1:27.*\nPin-Priority: 1001\n')
    config = pathlib.Path('/etc/rabbitmq')
    config.mkdir(mode=0o755, exist_ok=True)
    (config / 'rabbitmq.conf').write_text(
        'listeners.tcp.1 = 127.0.0.1:5672\n'
        'distribution.listener.interface = 127.0.0.1\n'
        'vm_memory_high_watermark.absolute = 160MiB\n'
        'disk_free_limit.absolute = 1GB\n'
        'channel_max = 32\nconnection_max = 20\n')
    (config / 'rabbitmq-env.conf').write_text('NODENAME=rabbit@localhost\nSERVER_ADDITIONAL_ERL_ARGS="+S 2:2 +A 4"\n')
    epmd = pathlib.Path('/etc/systemd/system/epmd.socket.d')
    epmd.mkdir(parents=True, exist_ok=True)
    (epmd / 'dictalab-loopback.conf').write_text('[Socket]\nListenStream=\nListenStream=127.0.0.1:4369\nListenStream=[::1]:4369\n')
    service = pathlib.Path('/etc/systemd/system/rabbitmq-server.service.d')
    service.mkdir(parents=True, exist_ok=True)
    (service / 'dictalab-resources.conf').write_text('[Service]\nRestart=on-failure\nRestartSec=5s\nMemoryHigh=256M\nMemoryMax=384M\n')
    run(['apt-get', 'install', '-y', 'rabbitmq-server=4.3.6-1'], timeout=300)
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'enable', '--now', 'rabbitmq-server'])
    run(['rabbitmq-diagnostics', '-q', 'ping'])
    credentials()


def credentials():
    if settings().get('RABBITMQ_USER') == 'dictalab_learning':
        raise RuntimeError('Credentials already provisioned; do not rotate implicitly')
    run(['rabbitmq-diagnostics', '-q', 'check_running'])
    password = secrets.token_urlsafe(32)
    salt = secrets.token_bytes(4)
    password_hash = base64.b64encode(salt + hashlib.sha256(salt + password.encode()).digest()).decode()
    vhost = 'dictalab-learning'
    definitions = {'vhosts': [{'name': vhost}], 'users': [{'name': 'dictalab_learning',
        'password_hash': password_hash, 'hashing_algorithm': 'rabbit_password_hashing_sha256', 'tags': []}],
        'permissions': [{'user': 'dictalab_learning', 'vhost': vhost,
            'configure': r'^dictalab\.learning\.evidence(\.dlq)?$',
            'write': r'^amq\.default$|^dictalab\.learning\.evidence(\.dlq)?$',
            'read': r'^dictalab\.learning\.evidence(\.dlq)?$'}]}
    os.umask(0o077)
    with tempfile.NamedTemporaryFile(mode='w', prefix='dictalab-definitions-', dir='/etc/rabbitmq', delete=False) as stream:
        json.dump(definitions, stream); name = stream.name
    os.chmod(name, 0o640); shutil.chown(name, user='root', group='rabbitmq')
    try:
        run(['rabbitmqctl', 'import_definitions', name])
    finally:
        pathlib.Path(name).unlink()
    # Default guest has no role in this dedicated broker; don't create a public management listener.
    run(['rabbitmqctl', 'delete_user', 'guest'])
    change_env({'RABBITMQ_HOST': '127.0.0.1', 'RABBITMQ_PORT': '5672', 'RABBITMQ_USER': 'dictalab_learning',
                'RABBITMQ_PASSWORD': password, 'SPRING_RABBITMQ_VIRTUAL_HOST': vhost,
                'DICTALAB_LEARNING_ENABLED': 'false', 'DICTALAB_LEARNING_QUEUE_ENABLED': 'false'})
    print(json.dumps({'installed': run(['rabbitmq-diagnostics', '-q', 'server_version']).strip(),
                      'learningEnabled': False, 'listeners': run(['rabbitmq-diagnostics', '-q', 'listeners']).strip()}))


def toggle(enabled):
    before = settings()
    if enabled:
        run(['rabbitmq-diagnostics', '-q', 'check_running'])
        if before.get('RABBITMQ_HOST') != '127.0.0.1' or before.get('RABBITMQ_PORT') != '5672':
            raise RuntimeError('Unexpected broker target')
    change_env({'DICTALAB_LEARNING_ENABLED': str(enabled).lower(), 'DICTALAB_LEARNING_QUEUE_ENABLED': str(enabled).lower()})
    print(json.dumps({'learningEnabled': enabled, 'serviceRestartRequired': True}))


def permissions():
    if settings().get('RABBITMQ_USER') != 'dictalab_learning':
        raise RuntimeError('Unexpected application broker identity')
    run(['rabbitmqctl', 'set_permissions', '-p', 'dictalab-learning', 'dictalab_learning',
         r'^dictalab\.learning\.evidence(\.dlq)?$',
         r'^amq\.default$|^dictalab\.learning\.evidence(\.dlq)?$',
         r'^dictalab\.learning\.evidence(\.dlq)?$'])
    print(run(['rabbitmqctl', 'list_permissions', '-p', 'dictalab-learning']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    actions = parser.add_mutually_exclusive_group(required=True)
    for action in ('prepare', 'install', 'credentials', 'permissions', 'enable', 'disable'):
        actions.add_argument('--' + action, action='store_true')
    args = parser.parse_args()
    if os.getuid() != 0:
        raise RuntimeError('Root required')
    if args.prepare: prepare()
    elif args.install: install()
    elif args.credentials: credentials()
    elif args.permissions: permissions()
    else: toggle(args.enable)
