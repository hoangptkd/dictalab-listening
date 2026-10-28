"""Prepare the initial Linux service identities/layout and install inactive units.

Uses an already staged, checksum-verified bundle. The separate --initialize-ledger
step creates a missing AI job ledger as its service identity. Never starts/enables
services, changes Core's JAR/config, or replaces an existing current link.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

spec = importlib.util.spec_from_file_location('service_bundle', Path(__file__).with_name('service-bundle.py'))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)

BASE = Path('/opt/dictalab')
UNITS = Path('/etc/systemd/system')
CONFIG = Path('/etc/dictalab')
STATE = Path('/var/lib/dictalab-ai')
BACKUPS = Path('/var/lib/dictalab-service-backups')
ENV = {'PATH': '/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/nonexistent', 'LANG': 'C.UTF-8'}


def run(command):
    result = subprocess.run(command, env=ENV, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError('Host preparation command failed; output suppressed')
    return result.stdout


def protected(path, *, private=False, directory=False):
    info = path.lstat()
    kind = stat.S_ISDIR if directory else stat.S_ISREG
    if not kind(info.st_mode) or info.st_uid != 0 or info.st_mode & (0o077 if private else 0o022):
        raise ValueError('Root-owned protected regular files/directories are required')


def ancestors(path):
    for parent in reversed((path, *path.parents)):
        protected(parent, directory=True)


def verify_stage(manifest, expected):
    release = BASE / 'services/releases' / manifest['sourceRevision']
    ancestors(release)
    if any(not parent.stat().st_mode & 0o001 for parent in (release, *release.parents) if parent != BASE):
        raise ValueError('Service identities cannot traverse release ancestors')
    for path in release.rglob('*'):
        relative = path.relative_to(release).as_posix()
        if path.is_symlink():
            # Standard venv Python/lib64 links may reference protected system files.
            if not re.fullmatch(r'venv/bin/python(?:3(?:\.\d+)?)?', relative) and relative != 'venv/lib64':
                raise ValueError('Only standard protected venv links are accepted')
            target = path.resolve(strict=True)
            if relative == 'venv/lib64' and target != release / 'venv/lib':
                raise ValueError('Venv lib64 must remain inside the isolated runtime')
            ancestors(target if target.is_dir() else target.parent)
            protected(target, directory=target.is_dir())
            if any(not parent.stat().st_mode & 0o001 for parent in target.parents if parent != BASE):
                raise ValueError('Service identities cannot traverse the Python runtime')
        else:
            protected(path, directory=path.is_dir())
            if path.is_file() and relative not in manifest['files'] and relative not in ('manifest.json', 'stage-ready.json') and not relative.startswith('venv/'):
                raise ValueError('Unexpected staged file')
            required_mode = 0o005 if path.is_dir() else 0o004
            if path.stat().st_mode & required_mode != required_mode:
                raise ValueError('Service identities must be able to read staged code')
    if release.stat().st_mode & 0o005 != 0o005:
        raise ValueError('Release must be published by successful staging')
    if json.loads((release / 'manifest.json').read_text(encoding='utf-8')) != manifest:
        raise ValueError('Staged manifest differs from verified bundle')
    ready = {'sourceRevision': manifest['sourceRevision'], 'bundleSha256': expected,
             'acceptance': 'staged_runtime_only_not_deployment'}
    if json.loads((release / 'stage-ready.json').read_text(encoding='utf-8')) != ready:
        raise ValueError('Staging acceptance marker is missing or mismatched')
    for name, info in manifest['files'].items():
        path = release / name
        if path.stat().st_size != info['bytes'] or bundle.digest(path) != info['sha256']:
            raise ValueError('Staged payload checksum mismatch')
    python = release / 'venv/bin/python'
    if not python.is_file() or not os.access(python, os.X_OK):
        raise ValueError('Staged Python runtime is missing')
    return release


def account(name, *, create=False):
    import grp
    import pwd
    try:
        user = pwd.getpwnam(name)
    except KeyError:
        try:
            grp.getgrnam(name)
        except KeyError:
            pass
        else:
            raise ValueError('Existing group requires manual identity review')
        if not create:
            return None
        run(['useradd', '--system', '--user-group', '--no-create-home', '--home-dir',
             '/nonexistent', '--shell', '/usr/sbin/nologin', name])
        user = pwd.getpwnam(name)
    group = grp.getgrgid(user.pw_gid)
    shadow = run(['getent', 'shadow', name]).strip().split(':')
    if (not 0 < user.pw_uid < 1000 or user.pw_gid == 0 or group.gr_name != name
            or user.pw_dir != '/nonexistent' or user.pw_shell not in ('/usr/sbin/nologin', '/sbin/nologin', '/bin/false')
            or set(os.getgrouplist(name, user.pw_gid)) != {user.pw_gid}
            or set(group.gr_mem) - {name} or len(shadow) != 9 or shadow[0] != name or not shadow[1].startswith(('!', '*'))
            or any(p.pw_name != name and (p.pw_uid == user.pw_uid or p.pw_gid == user.pw_gid) for p in pwd.getpwall())):
        raise ValueError('Existing service account is not an isolated locked system identity')
    return user


def inactive_uninstalled():
    for name in bundle.UNIT_FILES:
        result = run(['systemctl', 'show', name, '--property=LoadState,ActiveState,MainPID,DropInPaths'])
        fields = dict(line.split('=', 1) for line in result.splitlines() if '=' in line)
        if fields != {'LoadState': 'not-found', 'ActiveState': 'inactive', 'MainPID': '0', 'DropInPaths': ''}:
            raise ValueError('Initial preparation requires absent inactive units without overrides')


def prepare(path, expected):
    if sys.platform != 'linux' or os.geteuid() != 0 or sys.version_info < (3, 12):
        raise RuntimeError('Host preparation requires Linux root and Python 3.12 or later')
    manifest = bundle.verify_bundle(path, expected)
    if manifest['schemaVersion'] != 2:
        raise ValueError('Host preparation requires release format v2')
    release = verify_stage(manifest, expected)
    current = BASE / 'services/current'
    if current.exists() or current.is_symlink():
        raise ValueError('Initial preparation cannot replace an existing current release')
    ancestors(UNITS)
    ancestors(CONFIG)
    ancestors(STATE.parent)
    ancestors(BACKUPS.parent)
    for name in ('app.env', 'ai.env', 'progress.env'):
        protected(CONFIG / name, private=True)
    import grp
    core_gid = grp.getgrnam('dictalab').gr_gid
    for name in ('app', 'tools'):
        protected(BASE / name, directory=True)
        info = (BASE / name).stat()
        if info.st_gid != core_gid or stat.S_IMODE(info.st_mode) != 0o750:
            raise ValueError('Core app/tools must retain root:dictalab 0750 protection')
    if stat.S_IMODE(BASE.stat().st_mode) not in (0o750, 0o751):
        raise ValueError('Expected the established Core parent permission 0750 or 0751')
    for name in bundle.UNIT_FILES:
        override = UNITS / (name + '.d')
        roots = (UNITS, Path('/run/systemd/system'), Path('/usr/lib/systemd/system'), Path('/usr/local/lib/systemd/system'))
        if any(any(root.rglob(name)) for root in roots) or override.exists() or override.is_symlink():
            raise ValueError('Existing units/overrides require explicit release handling')
    inactive_uninstalled()
    users = {name: account(name) for name in ('dictalab-ai', 'dictalab-progress')}
    if STATE.exists() or STATE.is_symlink():
        info = STATE.lstat()
        user = users['dictalab-ai']
        if (not stat.S_ISDIR(info.st_mode) or not user or info.st_uid != user.pw_uid
                or info.st_gid != user.pw_gid or stat.S_IMODE(info.st_mode) != 0o700):
            raise ValueError('Existing AI state requires its dedicated account and mode 0700')
    if BACKUPS.exists() or BACKUPS.is_symlink():
        protected(BACKUPS, directory=True, private=True)
    # All read-only preflight gates pass before creating identities or installed files.
    users = {name: user or account(name, create=True) for name, user in users.items()}
    if not STATE.exists():
        STATE.mkdir(mode=0o700)
        os.chown(STATE, users['dictalab-ai'].pw_uid, users['dictalab-ai'].pw_gid)
    if not BACKUPS.exists():
        BACKUPS.mkdir(mode=0o700)
    BASE.chmod(0o751)
    # Link creation is exclusive. Subsequent releases need coordinated activation.
    current.symlink_to(release, target_is_directory=True)
    for name in bundle.UNIT_FILES:
        with (release / 'systemd' / name).open('rb') as source, (UNITS / name).open('xb') as target:
            target.write(source.read())
            target.flush()
            os.fsync(target.fileno())
        (UNITS / name).chmod(0o644)
    run(['systemd-analyze', 'verify', '--man=no', *[str(UNITS / name) for name in bundle.UNIT_FILES]])
    run(['systemctl', 'daemon-reload'])
    for name in bundle.UNIT_FILES:
        state = run(['systemctl', 'show', name, '--property=LoadState,ActiveState,MainPID'])
        if dict(line.split('=', 1) for line in state.splitlines() if '=' in line) != {
                'LoadState': 'loaded', 'ActiveState': 'inactive', 'MainPID': '0'}:
            raise RuntimeError('Installed service did not remain inactive; inspect host before continuing')
    return {'sourceRevision': manifest['sourceRevision'], 'bundleSha256': expected,
            'acceptance': 'initial-filesystem-and-inactive-units-only-not-deployment'}


def initialize_ledger(path, expected):
    if sys.platform != 'linux' or os.geteuid() != 0 or sys.version_info < (3, 12):
        raise RuntimeError('Ledger preparation requires Linux root and Python 3.12+')
    manifest = bundle.verify_bundle(path, expected)
    release = verify_stage(manifest, expected)
    current = BASE / 'services/current'
    if not current.is_symlink() or current.lstat().st_uid != 0 or current.resolve(strict=True) != release:
        raise ValueError('Ledger preparation requires the verified prepared current release')
    user = account('dictalab-ai')
    info = STATE.lstat()
    if (not user or not stat.S_ISDIR(info.st_mode) or info.st_uid != user.pw_uid
            or info.st_gid != user.pw_gid or stat.S_IMODE(info.st_mode) != 0o700):
        raise ValueError('Ledger preparation requires the private AI state directory')
    ancestors(STATE.parent)
    ancestors(CONFIG)
    ancestors(UNITS)
    spec = importlib.util.spec_from_file_location('ledger_backup_config', Path(__file__).with_name('cloud-backup.py'))
    backup = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backup)
    backup.SERVICE_ENVS = CONFIG
    ledger = STATE / 'jobs.sqlite3'
    if backup.runtime_settings('ai').get('DICTALAB_AI_JOBS_DATABASE', str(ledger)) != str(ledger):
        raise ValueError('AI configuration must use the installed unit ledger path')
    members = [ledger.with_name(ledger.name + suffix) for suffix in ('', '-wal', '-shm', '-journal')]
    if any(member.exists() or member.is_symlink() for member in members):
        raise ValueError('Existing ledger or sidecar requires inspection; initialization never overwrites it')
    def inactive():
        for name in ('dictalab-ai.service', 'dictalab-ai-worker.service'):
            protected(UNITS / name)
            if bundle.digest(UNITS / name) != manifest['files']['systemd/' + name]['sha256']:
                raise ValueError('Ledger initialization requires exact installed AI units')
            result = run(['systemctl', 'show', name,
                '--property=LoadState,ActiveState,MainPID,FragmentPath,DropInPaths,NeedDaemonReload'])
            if dict(line.split('=', 1) for line in result.splitlines() if '=' in line) != {
                    'LoadState': 'loaded', 'ActiveState': 'inactive', 'MainPID': '0',
                    'FragmentPath': str(UNITS / name), 'DropInPaths': '', 'NeedDaemonReload': 'no'}:
                raise ValueError('AI processes must be loaded, unchanged and inactive for ledger preparation')
    inactive()
    # Import the verified release as the unprivileged account; never load runtime
    # credentials or instantiate Settings just to create the existing JobStore schema.
    code = '''import os, sys
os.umask(0o077)
sys.path.insert(0, sys.argv[1])
from app.jobs import JobStore
fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
os.close(fd)
store = JobStore(sys.argv[2])
with store.connect() as db:
    if db.execute("PRAGMA integrity_check").fetchall()[0][0] != "ok":
        raise RuntimeError("New ledger integrity failed")
'''
    run(['runuser', '-u', user.pw_name, '--', str(release / 'venv/bin/python'), '-I', '-B', '-c', code,
         str(release / 'ai'), str(ledger)])
    inactive()
    verify_stage(manifest, expected)
    if current.resolve(strict=True) != release:
        raise RuntimeError('Current release changed during ledger preparation')
    for member in members:
        if member.exists() or member.is_symlink():
            info = member.lstat()
            if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
                    or info.st_uid != user.pw_uid or info.st_gid != user.pw_gid
                    or stat.S_IMODE(info.st_mode) != 0o600):
                raise RuntimeError('Ledger ownership or private permissions were not preserved')
    if not ledger.is_file() or ledger.stat().st_size == 0:
        raise RuntimeError('Ledger was not initialized')
    return {'sourceRevision': manifest['sourceRevision'], 'bundleSha256': expected,
            'acceptance': 'new-private-ledger-only-not-database-or-deployment-acceptance'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--initialize-ledger', action='store_true',
                        help='After host preparation, exclusively initialize a missing private AI ledger')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        action = initialize_ledger if args.initialize_ledger else prepare
        print(json.dumps(action(args.bundle, args.sha256)))
    except Exception as error:
        raise SystemExit('SERVICE_PROVISION_FAILED: ' + type(error).__name__ +
                         '; preparation may be partial; no services started or enabled; inspect before retry') from None


if __name__ == '__main__':
    main()
