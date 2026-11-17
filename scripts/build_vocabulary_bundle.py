"""Build the reviewed local source and labels into an offline catalog import bundle."""
import argparse
import json
from pathlib import Path
from vocabulary_source_preview import parse_snapshot, classify_status, digest


def build(folder):
    folder = Path(folder)
    raw = json.loads((folder / 'source.json').read_text(encoding='utf-8-sig'))
    parsed = parse_snapshot(folder / 'source.json')
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8-sig'))
    if manifest['sha256'] != parsed['sha256']:
        raise ValueError('Source checksum changed; review the manifest before building')
    labels = json.loads((folder / 'cefr-reference-v1.json').read_text(encoding='utf-8-sig'))
    status = classify_status(parsed['records'], folder / 'cefr-reference-v1.json')
    if status['unclassified'] or status['stale']:
        raise ValueError('Missing or stale classifications; only classify changed entries')
    spec = json.loads((folder / 'collection.json').read_text(encoding='utf-8-sig'))
    if len(set(spec['levels'])) != len(spec['levels']):
        raise ValueError('Duplicate configured level')
    collection = spec['id']
    nodes = [dict(id=collection, parentId=None, title=spec['title'], level='', order=0, hasDeck=True)]
    topics = {(part_key, str(topic['code'])): topic for part_key, part in raw['parts'].items() for topic in part['topics']}
    groups = {}
    for order, level in enumerate(spec['levels']):
        level_id = collection + '-' + digest(level)[:12]
        nodes.append(dict(id=level_id, parentId=collection, title=level, level=level, order=order, hasDeck=False))
        for topic_order, (scope, topic) in enumerate(topics.items()):
            if not any(record['scope'][:2] == scope and labels[key]['level'] == level for key, record in parsed['records'].items()):
                continue
            group = level_id + '-' + digest(json.dumps(scope))[:12]
            groups[(level, *scope)] = group
            nodes.append(dict(id=group, parentId=level_id, title=topic['name_english'], level=level, order=topic_order, hasDeck=True))
    entries = []
    for source_key, record in parsed['records'].items():
        label = labels[source_key]
        level = label['level']
        if level not in spec['levels']:
            raise ValueError('Classification level is not configured for this collection')
        source = dict(english=record['english'], vietnamese=record['vietnamese'], notes=record['notes'],
                      part=record['scope'][0], topic=record['scope'][1], subtopic=record['scope'][2], content_hash=record['content_hash'])
        note = dict(term=record['english'], meaningVi=record['vietnamese'], partOfSpeech='', exampleEn='', exampleVi='',
                    tags='', alternatives=spec['alternatives'].get(record['english'], []), revision=0, meaningRecall=False, usageNotes=record['notes'])
        entries.append(dict(sourceKey=source_key, nodeId=groups[(level, *record['scope'][:2])], subtopic=record['scope'][2],
                            note=note, sourceJson=json.dumps(source, ensure_ascii=False, separators=(',', ':')),
                            classificationJson=json.dumps(label, ensure_ascii=False, separators=(',', ':'))))
    if len(entries) != len(parsed['records']):
        raise ValueError('Reconciliation failed')
    return dict(collectionId=collection, nodes=nodes, entries=entries, sourceSha256=parsed['sha256'], attribution=spec['attribution'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = build(args.folder)
    destination = Path(args.folder) / 'bundle.json'
    text = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.check:
        if destination.read_text(encoding='utf-8') != text:
            raise SystemExit('Bundle is stale')
    else:
        destination.write_text(text, encoding='utf-8', newline='\n')
    print(f"Reconciled {len(result['entries'])} entries, {len(result['nodes'])} navigation nodes")
