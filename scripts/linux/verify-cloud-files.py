"""Read-only checksums of the copied media; does not read environment/credential files."""
import hashlib
import json
import pathlib

rows = []
for name in ('media', 'personal-content'):
    root = pathlib.Path('/var/lib/dictalab') / name
    if not root.is_dir():
        raise SystemExit('Required media directory is missing')
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise SystemExit('Symlink encountered in media snapshot')
        if path.is_file():
            with path.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            rows.append({'path': name + '/' + path.relative_to(root).as_posix(),
                         'bytes': path.stat().st_size, 'sha256': digest})
print(json.dumps(rows))
