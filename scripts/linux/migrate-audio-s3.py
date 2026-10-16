"""Copy production audio to the configured private bucket, then publish stable aliases.

Run as root: prepare PLAN | activate PLAN | verify PLAN.
prepare never changes lesson/history references. activate requires a stopped service.
Credentials come exclusively from protected service configuration. Local files remain backups.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import uuid
import zipfile

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


def digest(data):
    return hashlib.sha256(data).hexdigest()


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def settings():
    result = {}
    for line in Path('/etc/dictalab/app.env').read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            result[key] = shlex.split(value)[0]
    return result


def database(sql):
    spec = importlib.util.spec_from_file_location('backup', '/opt/dictalab/tools/cloud-backup.py')
    backup = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backup)
    result = subprocess.run(['/usr/lib/postgresql/18/bin/psql', '-XqAt', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, text=True, capture_output=True, env=backup.connection_environment(), timeout=120)
    if result.returncode:
        raise RuntimeError('Database operation failed; details suppressed')
    return result.stdout.strip()


def client(config):
    for key in ('AWS_SHARED_CREDENTIALS_FILE', 'AWS_PROFILE', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN'):
        if key in config:
            os.environ[key] = config[key]
    s3 = boto3.client('s3', region_name=config['DICTALAB_MEDIA_S3_REGION'],
                      config=Config(connect_timeout=10, read_timeout=45, retries={'total_max_attempts': 1}))
    # The installed SDK predates the modeled IfNoneMatch parameter; sign the native HTTP header.
    s3.meta.events.register('before-call.s3.PutObject',
                           lambda params, **kwargs: params['headers'].update({'If-None-Match': '*'}))
    return s3


def verify_object(s3, bucket, prefix, item, data=None):
    key = prefix + item['object_name']
    try:
        head = s3.head_object(Bucket=bucket, Key=key)
    except ClientError as error:
        # Object-only IAM returns 403 for a missing key when ListBucket is intentionally absent.
        if error.response['ResponseMetadata']['HTTPStatusCode'] not in (403, 404) or data is None:
            raise RuntimeError('S3 object unavailable; details suppressed') from None
        try:
            s3.put_object(Bucket=bucket, Key=key, Body=data,
                          ContentType='audio/wav' if key.endswith('.wav') else ('audio/mp4' if key.endswith('.m4a') else 'audio/mpeg'),
                          Metadata={'sha256': item['sha256']}, ChecksumSHA256=base64.b64encode(hashlib.sha256(data).digest()).decode())
        except ClientError as put_error:
            if put_error.response['ResponseMetadata']['HTTPStatusCode'] != 412:
                raise RuntimeError('S3 conditional upload failed; details suppressed') from None
        head = s3.head_object(Bucket=bucket, Key=key)
    if head['ContentLength'] != item['byte_count'] or head['Metadata'].get('sha256') != item['sha256']:
        raise RuntimeError('Immutable S3 object metadata mismatch')
    body = s3.get_object(Bucket=bucket, Key=key)['Body']
    try:
        sha = hashlib.sha256()
        size = 0
        while chunk := body.read(1024 * 1024):
            sha.update(chunk)
            size += len(chunk)
    finally:
        body.close()
    if sha.hexdigest() != item['sha256'] or size != item['byte_count']:
        raise RuntimeError('S3 readback checksum mismatch')


def stable_name(url, sha, namespace):
    extension = Path(url).suffix.lower()
    if extension not in ('.mp3', '.m4a', '.wav'):
        raise RuntimeError('Unsupported audio format')
    return namespace + '/' + str(uuid.uuid5(uuid.NAMESPACE_URL, url + ':' + sha)) + extension


def inventory(config):
    assets = {}
    for base, route in ((config['DICTALAB_MEDIA_ROOT'], '/media/'),
                        (config['DICTALAB_PERSONAL_CONTENT_ROOT'], '/private-media/')):
        root = Path(base).resolve(strict=True)
        for file in root.rglob('*'):
            if file.suffix.lower() in ('.mp3', '.m4a', '.wav', '.ogg', '.aac', '.flac'):
                if file.is_symlink() or not file.is_file() or not file.resolve().is_relative_to(root):
                    raise RuntimeError('Unsafe audio source')
                assets[route + file.relative_to(root).as_posix()] = {'file': str(file)}
    with zipfile.ZipFile('/opt/dictalab/app/dictalab.jar') as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/classes/static/') and Path(name).suffix.lower() in ('.mp3', '.m4a', '.wav', '.ogg', '.aac', '.flac'):
                assets['/' + name.removeprefix('BOOT-INF/classes/static/')] = {'jar_entry': name}
    # One existing sentence used the correct immutable filename under the wrong folder.
    typo = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-04-4-Will-Game.mp3'
    correct = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3'
    if correct in assets:
        assets[typo] = dict(assets[correct], source_alias=correct)
    references = json.loads(database("SELECT coalesce(json_agg(DISTINCT audio_url),'[]'::json) FROM (SELECT audio_url FROM lesson_sentences UNION ALL SELECT audio_url FROM listening_units UNION ALL SELECT audio_url FROM listening_review_attempts UNION ALL SELECT audio_url FROM audio_exposure_events) q WHERE audio_url IS NOT NULL;"))
    stored = set(json.loads(database("SELECT coalesce(json_agg(audio_url),'[]'::json) FROM audio_s3_aliases;")))
    missing = [url for url in references if not url.startswith('/media/s3/') and url not in assets and url not in stored]
    if missing:
        raise RuntimeError('Audio sources missing: ' + json.dumps(missing))
    return assets


def conversation_rows():
    return json.loads(database("SELECT coalesce(json_agg(t),'[]'::json) FROM (SELECT session_id,turn_number,variant,encode(wav,'base64') AS wave,s3_audio_url FROM ai_conversation_audio ORDER BY session_id,turn_number,variant)t;"))


def unchanged_content():
    result = {}
    for table in ('lessons', 'lesson_sentences', 'listening_units', 'lesson_preview_words', 'library_courses',
                  'library_groups', 'library_lessons', 'library_lesson_metadata', 'practice_sessions',
                  'sentence_attempts', 'listening_sessions', 'listening_review_attempts', 'audio_exposure_events', 'ai_conversation_sessions'):
        # Sort serialized rows rather than making assumptions about composite primary keys.
        result[table] = database('SELECT count(*)::text||\':\'||coalesce(md5(string_agg(row_to_json(t)::text,\'\' ORDER BY row_to_json(t)::text)),\'empty\') FROM ' + table + ' t;')
    return result


def stopped():
    result = subprocess.run(['systemctl', 'is-active', 'dictalab'], capture_output=True, text=True, timeout=15)
    if result.stdout.strip() != 'inactive':
        raise RuntimeError('Activation requires stopped service and quiescent backup')


def prepare(plan, config, s3):
    if plan.exists():
        previous = json.loads(plan.read_text())
        if previous['identity'] != config['DICTALAB_MEDIA_S3_BUCKET'] + '/' + config['DICTALAB_MEDIA_S3_PREFIX']:
            raise RuntimeError('Storage identity changed')
    result = {'identity': config['DICTALAB_MEDIA_S3_BUCKET'] + '/' + config['DICTALAB_MEDIA_S3_PREFIX'],
              'aliases': [], 'conversations': []}
    sources = inventory(config)
    # Published assets may have been removed from disk after a verified migration.
    for item in json.loads(database("SELECT coalesce(json_agg(t),'[]'::json) FROM audio_s3_aliases t;")):
        if item['audio_url'] not in sources:
            verify_object(s3, config['DICTALAB_MEDIA_S3_BUCKET'], config['DICTALAB_MEDIA_S3_PREFIX'], item)
            result['aliases'].append(item)
    with zipfile.ZipFile('/opt/dictalab/app/dictalab.jar') as jar:
        for url, source in sources.items():
            data = Path(source['file']).read_bytes() if 'file' in source else jar.read(source['jar_entry'])
            sha = digest(data)
            item = {'audio_url': url, 'object_name': stable_name(source.get('source_alias', url), sha, 'legacy'),
                    'sha256': sha, 'byte_count': len(data), **source}
            verify_object(s3, config['DICTALAB_MEDIA_S3_BUCKET'], config['DICTALAB_MEDIA_S3_PREFIX'], item, data)
            result['aliases'].append(item)
            if len(result['aliases']) % 20 == 0:
                print('VERIFIED_LEGACY_AUDIO=' + str(len(result['aliases'])), flush=True)
    for row in conversation_rows():
        if row['wave'] is None:
            if not row['s3_audio_url']:
                raise RuntimeError('Conversation source missing')
            continue
        data = base64.b64decode(row.pop('wave'))
        route = '/api/v1/conversation/sessions/{session_id}/audio/{turn_number}/{variant}'.format(**row)
        item = {**row, 'object_name': stable_name(route + '.wav', digest(data), 'conversation'),
                'sha256': digest(data), 'byte_count': len(data)}
        item['s3_audio_url'] = '/conversation-media/s3/' + item['object_name'].split('/')[1]
        # Durable intent precedes PUT; metadata/readback allow resuming an uncertain upload.
        database("INSERT INTO s3_media_lifecycle(audio_url,storage_identity,status,upload_until,cleanup_after) VALUES(" + quote(item['s3_audio_url']) + ',' + quote(result['identity']) + ",'STAGED',CURRENT_TIMESTAMP+INTERVAL '10 MINUTE',CURRENT_TIMESTAMP+INTERVAL '7 DAY') ON CONFLICT DO NOTHING;")
        valid = database("SELECT count(*) FROM s3_media_lifecycle WHERE audio_url=" + quote(item['s3_audio_url']) + ' AND storage_identity=' + quote(result['identity']) + " AND status IN ('STAGED','LIVE');")
        if valid != '1':
            raise RuntimeError('Conversation upload identity was retired or changed')
        verify_object(s3, config['DICTALAB_MEDIA_S3_BUCKET'], config['DICTALAB_MEDIA_S3_PREFIX'], item, data)
        result['conversations'].append(item)
    plan.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    os.chmod(plan, 0o600)
    print(json.dumps({'plan': str(plan), 'aliases': len(result['aliases']), 'conversations': len(result['conversations'])}))


def activate(plan, config, s3):
    stopped()
    data = json.loads(plan.read_text())
    if data['identity'] != config['DICTALAB_MEDIA_S3_BUCKET'] + '/' + config['DICTALAB_MEDIA_S3_PREFIX']:
        raise RuntimeError('Storage identity changed')
    before = unchanged_content()
    sources = inventory(config)
    if not set(sources).issubset({item['audio_url'] for item in data['aliases']}):
        raise RuntimeError('Audio inventory changed; prepare again')
    with zipfile.ZipFile('/opt/dictalab/app/dictalab.jar') as jar:
        for item in data['aliases']:
            if item['audio_url'] not in sources:
                stored = database('SELECT count(*) FROM audio_s3_aliases WHERE audio_url=' + quote(item['audio_url'])
                                  + ' AND object_name=' + quote(item['object_name']) + ' AND sha256=' + quote(item['sha256'])
                                  + ' AND byte_count=' + str(item['byte_count']) + ';')
                if stored != '1':
                    raise RuntimeError('Published S3-only alias changed')
                continue
            source = sources[item['audio_url']]
            wave = Path(source['file']).read_bytes() if 'file' in source else jar.read(source['jar_entry'])
            if digest(wave) != item['sha256']:
                raise RuntimeError('Audio source changed; prepare again')
    current = conversation_rows()
    planned = {(x['session_id'], x['turn_number'], x['variant']): x for x in data['conversations']}
    for row in current:
        if row['wave'] is not None:
            item = planned.get((row['session_id'], row['turn_number'], row['variant']))
            if not item or digest(base64.b64decode(row['wave'])) != item['sha256']:
                raise RuntimeError('Conversation changed during preparation; prepare again')
    for item in data['aliases'] + data['conversations']:
        verify_object(s3, config['DICTALAB_MEDIA_S3_BUCKET'], config['DICTALAB_MEDIA_S3_PREFIX'], item)
    sql = ['BEGIN;']
    for item in data['aliases']:
        sql.append('INSERT INTO audio_s3_aliases(audio_url,object_name,sha256,byte_count) VALUES (' + ','.join((quote(item['audio_url']), quote(item['object_name']), quote(item['sha256']), str(item['byte_count']))) + ') ON CONFLICT(audio_url) DO UPDATE SET object_name=excluded.object_name,sha256=excluded.sha256,byte_count=excluded.byte_count;')
    for item in data['conversations']:
        sql.append("UPDATE ai_conversation_audio SET s3_audio_url=" + quote(item['s3_audio_url']) + ',wav=NULL WHERE session_id=' + quote(item['session_id']) + ' AND turn_number=' + str(item['turn_number']) + ' AND variant=' + quote(item['variant']) + ';')
        sql.append("UPDATE s3_media_lifecycle SET status='LIVE',upload_until=NULL,cleanup_after=NULL WHERE audio_url=" + quote(item['s3_audio_url']) + ";")
    sql.append('COMMIT;')
    database('\n'.join(sql))
    if unchanged_content() != before:
        raise RuntimeError('Non-storage data changed')
    data['activated'] = True
    data['unchangedContent'] = before
    plan.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    print('AUDIO_S3_ACTIVATED; CONTENT_HISTORY_UNCHANGED')


def verify(plan, config, s3):
    data = json.loads(plan.read_text())
    for item in data['aliases'] + data['conversations']:
        verify_object(s3, config['DICTALAB_MEDIA_S3_BUCKET'], config['DICTALAB_MEDIA_S3_PREFIX'], item)
    aliases = json.loads(database("SELECT coalesce(json_agg(t),'[]'::json) FROM audio_s3_aliases t;"))
    assert all(any(x == {k: item[k] for k in ('audio_url', 'object_name', 'sha256', 'byte_count')} for x in aliases) for item in data['aliases'])
    assert database('SELECT count(*) FROM ai_conversation_audio WHERE wav IS NOT NULL OR s3_audio_url IS NULL;') == '0'
    urls = inventory(config)
    assert all(any(x['audio_url'] == url for x in aliases) for url in urls)
    print(json.dumps({'verifiedUniqueObjects': len({x['object_name'] for x in data['aliases'] + data['conversations']}),
                      'aliases': len(data['aliases']), 'conversationAudio': len(data['conversations']), 'databaseWavRemaining': 0}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'activate', 'verify'))
    parser.add_argument('plan', type=Path)
    args = parser.parse_args()
    if os.getuid() != 0:
        raise RuntimeError('Protected configuration requires root')
    os.umask(0o077)
    path = args.plan.resolve()
    if not path.is_relative_to(Path('/var/lib/dictalab/backups')) or not path.parent.is_dir() or path.is_symlink():
        raise RuntimeError('Plan must be under protected backups directory')
    config = settings()
    if config.get('DICTALAB_MEDIA_S3_ENABLED') != 'true':
        raise RuntimeError('S3 is disabled')
    globals()[args.mode](path, config, client(config))


if __name__ == '__main__':
    main()
