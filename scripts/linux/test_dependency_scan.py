import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('dependency_scan', pathlib.Path(__file__).parent.parent / 'scan-dependencies.py')
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)


class DependencyScanTest(unittest.TestCase):
    def test_resolved_versions_classifiers_and_deduplication(self):
        data = '\n'.join(['Resolved artifacts:', ' org.example:one:jar:1.2:compile',
                          ' org.example:one:jar:1.2:compile', 'org.example:two:jar:linux:2.3:runtime',
                          'org.example:test:jar:1.0:test'])
        self.assertEqual([('org.example:one', '1.2'), ('org.example:two', '2.3')], scan.coordinates(data))
        with self.assertRaises(ValueError):
            scan.coordinates('No dependency resolution output')

    def test_vulnerable_package_fails_and_clean_response_is_explicit(self):
        packages = [('org.example:one', '1.2')]
        report = scan.scan(packages, lambda payload: {'results': [{'vulns': [{'id': 'TEST-ADVISORY'}]}]})
        self.assertEqual(['TEST-ADVISORY'], report['findings'][0]['ids'])
        self.assertEqual([], scan.scan(packages, lambda payload: {'results': [{}]})['findings'])

    def test_incomplete_and_paginated_responses_do_not_pass(self):
        for response in ({}, {'results': []}, {'results': [{'next_page_token': 'more'}]},
                         {'results': [{'vulns': 'malformed'}]}):
            with self.subTest(response=response), self.assertRaises(ValueError):
                scan.scan([('org.example:one', '1.2')], lambda payload: response)


if __name__ == '__main__':
    unittest.main()
