"""Reject releases that reintroduce local audio or omit published lesson pages."""
import sys
import zipfile


def verify(path):
    with zipfile.ZipFile(path) as jar:
        entries = set(jar.namelist())
        static = 'BOOT-INF/classes/static/'
        audio = {'.mp3', '.wav', '.m4a', '.ogg', '.aac', '.flac'}
        if any(name.startswith(static) and any(name.lower().endswith(ext) for ext in audio)
               for name in entries):
            raise ValueError('Production JAR contains local audio already served from S3')
        for folder, count in [('b1-expansion-20261015', 91), ('listening-samples-20261015', 1)]:
            pages = [name for name in entries if name.startswith(static + 'audio/' + folder + '/')
                     and name.endswith('.html')]
            if len(pages) != count:
                raise ValueError('Published lesson pages missing: ' + folder)
        if 'BOOT-INF/classes/db/migration/V35__all_audio_s3.sql' not in entries:
            raise ValueError('S3 schema migration missing')
    print('Production JAR verified: S3 audio, schema and all published lesson pages retained')


if __name__ == '__main__':
    verify(sys.argv[1])
