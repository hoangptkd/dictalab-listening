"""Scan resolved Maven runtime coordinates with OSV; no account or scanner dependency.

mvn dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt
python scripts/scan-dependencies.py target/runtime-dependencies.txt target/dependency-scan.json
Only public package coordinates are sent. Incomplete/unavailable scans fail closed.
"""
import argparse
import datetime
import json
import pathlib
import re
import urllib.request


def coordinates(text):
    packages = set()
    for line in text.splitlines():
        value = line.strip().split(' -- ', 1)[0]
        parts = value.split(':')
        if len(parts) not in (5, 6) or parts[-1] not in ('compile', 'runtime'):
            continue
        group, artifact = parts[:2]
        version = parts[-2]
        if not re.fullmatch(r'[\w.-]+', group) or not re.fullmatch(r'[\w.-]+', artifact) or not version or '${' in version:
            raise ValueError('Invalid resolved Maven coordinate')
        packages.add((group + ':' + artifact, version))
    if not packages:
        raise ValueError('No resolved runtime dependencies; refusing an empty scan')
    return sorted(packages)


def scan(packages, query=None):
    if query is None:
        def query(payload):
            request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
                    data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=45) as response:
                data = response.read(2_000_001)
            if len(data) > 2_000_000:
                raise ValueError('Oversized vulnerability response')
            return json.loads(data)
    findings = []
    for offset in range(0, len(packages), 100):
        batch = packages[offset:offset + 100]
        response = query({'queries': [{'package': {'ecosystem': 'Maven', 'name': name}, 'version': version}
                                     for name, version in batch]})
        results = response.get('results')
        if not isinstance(results, list) or len(results) != len(batch):
            raise ValueError('Incomplete vulnerability response')
        for (name, version), result in zip(batch, results):
            if not isinstance(result, dict) or result.get('next_page_token'):
                raise ValueError('Incomplete/paginated scan; use OSV-Scanner for this dependency set')
            vulnerabilities = result.get('vulns', [])
            if not isinstance(vulnerabilities, list) or any(not isinstance(item, dict) or not item.get('id') for item in vulnerabilities):
                raise ValueError('Malformed vulnerability list')
            if vulnerabilities:
                findings.append({'package': name, 'version': version,
                                 'ids': sorted(set(item['id'] for item in vulnerabilities))})
    return {'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'source': 'OSV', 'runtimePackages': len(packages), 'findings': findings,
            'limitations': 'Known advisory matching only; not a source security audit or proof of no vulnerabilities'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('dependencies', type=pathlib.Path)
    parser.add_argument('report', type=pathlib.Path)
    args = parser.parse_args()
    report = scan(coordinates(args.dependencies.read_text(encoding='utf-8')))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'runtimePackages': report['runtimePackages'], 'affectedPackages': len(report['findings'])}))
    if report['findings']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
