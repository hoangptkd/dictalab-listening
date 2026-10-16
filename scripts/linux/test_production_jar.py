import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('production_jar', Path(__file__).parents[1] / 'verify-production-jar.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ProductionJarTest(unittest.TestCase):
    def test_rejects_audio_and_missing_published_content(self):
        with tempfile.TemporaryDirectory() as root:
            jar = Path(root) / 'application.jar'
            names = ['BOOT-INF/classes/db/migration/V35__all_audio_s3.sql']
            names += ['BOOT-INF/classes/static/audio/b1-expansion-20261015/' + str(i) + '.html' for i in range(91)]
            names += ['BOOT-INF/classes/static/audio/listening-samples-20261015/index.html']
            def write(entries):
                with zipfile.ZipFile(jar, 'w') as archive:
                    for name in entries:
                        archive.writestr(name, b'fixture')
            write(names)
            module.verify(jar)
            write(names + ['BOOT-INF/classes/static/audio/elllo/old.mp3'])
            with self.assertRaises(ValueError):
                module.verify(jar)
            write(names[:-1])
            with self.assertRaises(ValueError):
                module.verify(jar)


if __name__ == '__main__':
    unittest.main()
