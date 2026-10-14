"""Reuse unchanged ZIP payloads across slow SSH links; reconstructed bytes must match SHA-256."""
import argparse
import hashlib
import json
import pathlib
import struct
import tempfile
import zipfile


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def segments(path):
    result = []
    with zipfile.ZipFile(path) as archive, path.open('rb') as stream:
        for item in archive.infolist():
            if item.compress_size < 4096:
                continue
            stream.seek(item.header_offset)
            header = stream.read(30)
            if header[:4] != b'PK\x03\x04':
                raise ValueError('Invalid local ZIP header')
            names, extra = struct.unpack('<HH', header[26:30])
            offset = item.header_offset + 30 + names + extra
            stream.seek(offset)
            data = stream.read(item.compress_size)
            if len(data) != item.compress_size:
                raise ValueError('Truncated ZIP payload')
            result.append((offset, len(data), hashlib.sha256(data).hexdigest()))
    return sorted(result)


def index(path):
    return {'sha256': digest(path), 'segments': segments(path)}


def pack(candidate, baseline, bundle):
    lookup = {(length, sha): offset for offset, length, sha in baseline['segments']}
    recipe = {'baseSha256': baseline['sha256'], 'sha256': digest(candidate),
              'bytes': candidate.stat().st_size, 'pieces': []}
    with candidate.open('rb') as source, bundle.open('xb') as output:
        cursor = 0
        for offset, length, sha in segments(candidate):
            if offset < cursor:
                raise ValueError('Overlapping ZIP payloads')
            if offset > cursor:
                source.seek(cursor)
                gap = source.read(offset - cursor)
                recipe['pieces'].append(['bundle', output.tell(), len(gap)])
                output.write(gap)
            if (length, sha) in lookup:
                recipe['pieces'].append(['base', lookup[length, sha], length])
            else:
                source.seek(offset)
                recipe['pieces'].append(['bundle', output.tell(), length])
                output.write(source.read(length))
            cursor = offset + length
        source.seek(cursor)
        tail = source.read()
        recipe['pieces'].append(['bundle', output.tell(), len(tail)])
        output.write(tail)
    return recipe


def reconstruct(base, bundle, recipe, destination):
    if destination.exists() or destination.is_symlink():
        raise ValueError('Destination must not exist')
    if digest(base) != recipe['baseSha256']:
        raise ValueError('Baseline checksum mismatch')
    if not 0 < recipe['bytes'] <= 256 * 1024**2 or len(recipe['pieces']) > 50000:
        raise ValueError('Transfer exceeds bounds')
    created = False
    try:
        with base.open('rb') as old, bundle.open('rb') as delta, destination.open('xb') as target:
            created = True
            sources = {'base': old, 'bundle': delta}
            written = 0
            for kind, offset, length in recipe['pieces']:
                if kind not in sources or offset < 0 or length < 0 or written + length > recipe['bytes']:
                    raise ValueError('Invalid transfer range')
                stream = sources[kind]
                stream.seek(offset)
                remaining = length
                while remaining:
                    data = stream.read(min(remaining, 1024**2))
                    if not data:
                        raise ValueError('Truncated transfer')
                    target.write(data)
                    remaining -= len(data)
                written += length
            if written != recipe['bytes']:
                raise ValueError('Transfer length mismatch')
        if digest(destination) != recipe['sha256']:
            raise ValueError('Reconstructed checksum mismatch')
    except Exception:
        if created:
            destination.unlink()
        raise


def self_test():
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        for name, changed in [('base', b'old'), ('candidate', b'new')]:
            with zipfile.ZipFile(root / name, 'w') as archive:
                archive.writestr('unchanged', b'x' * 20000)
                archive.writestr('changed', changed)
        recipe = pack(root / 'candidate', index(root / 'base'), root / 'bundle')
        assert (root / 'bundle').stat().st_size < 1000
        reconstruct(root / 'base', root / 'bundle', recipe, root / 'output')
        assert (root / 'output').read_bytes() == (root / 'candidate').read_bytes()
        try:
            reconstruct(root / 'base', root / 'bundle', recipe, root / 'output')
            raise AssertionError('Existing output overwritten')
        except ValueError:
            pass
        (root / 'bundle').write_bytes(b'corrupt')
        try:
            reconstruct(root / 'base', root / 'bundle', recipe, root / 'failed')
            raise AssertionError('Corruption accepted')
        except ValueError:
            assert not (root / 'failed').exists()
    print('ARTIFACT_TRANSFER_SELF_TEST=PASS')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['index', 'pack', 'apply', 'self-test'])
    parser.add_argument('paths', nargs='*', type=pathlib.Path)
    args = parser.parse_args()
    if args.mode == 'self-test':
        self_test()
    elif args.mode == 'index':
        print(json.dumps(index(args.paths[0])))
    elif args.mode == 'pack':
        candidate, baseline, bundle, manifest = args.paths
        recipe = pack(candidate, json.loads(baseline.read_text(encoding='utf-8-sig')), bundle)
        manifest.write_text(json.dumps(recipe), encoding='utf-8')
        print(json.dumps({'artifactBytes': recipe['bytes'], 'transferBytes': bundle.stat().st_size}))
    else:
        base, bundle, manifest, output = args.paths
        reconstruct(base, bundle, json.loads(manifest.read_text(encoding='utf-8')), output)
        print('ARTIFACT_RECONSTRUCTED_SHA256=' + digest(output))


if __name__ == '__main__':
    main()
