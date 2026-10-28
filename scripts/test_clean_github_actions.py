"""Tests for scripts/clean-github-actions.py selection helpers (no network access)."""

import importlib.util
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCRIPT = Path(__file__).with_name("clean-github-actions.py")
spec = importlib.util.spec_from_file_location("clean_github_actions", SCRIPT)
cleaner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleaner)


def run(path=".github/workflows/ios.yml", name="Build personal iOS app", **extra):
    payload = {"path": path, "name": name, "id": extra.pop("id", 1)}
    payload.update(extra)
    return payload


class WorkflowKeyTests(unittest.TestCase):
    def test_basename(self):
        self.assertEqual(cleaner.workflow_key(run()), "ios.yml")

    def test_missing_path(self):
        self.assertEqual(cleaner.workflow_key({"name": "x"}), "")


class MatchesWorkflowTests(unittest.TestCase):
    def test_no_selector_matches_everything(self):
        self.assertTrue(cleaner.matches_workflow(run(), []))

    def test_matches_file_name(self):
        self.assertTrue(cleaner.matches_workflow(run(), ["ios.yml"]))
        self.assertTrue(cleaner.matches_workflow(run(), ["training-offline.yml", "ios.yml"]))

    def test_matches_display_name(self):
        self.assertTrue(cleaner.matches_workflow(run(), ["Build personal iOS app"]))

    def test_rejects_other_workflow(self):
        self.assertFalse(cleaner.matches_workflow(run(), ["deploy.yml"]))


class AgeTests(unittest.TestCase):
    def test_age_days_parses_github_timestamp(self):
        created = (datetime.now(timezone.utc) - timedelta(days=10)).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.assertGreater(cleaner.iso_age_days(created), 9.9)

    def test_age_days_invalid(self):
        self.assertIsNone(cleaner.iso_age_days("not-a-date"))
        self.assertIsNone(cleaner.iso_age_days(None))

    def test_zero_min_age_allows_everything(self):
        self.assertTrue(cleaner.older_than({"created_at": "2020-01-01T00:00:00Z"}, 0))

    def test_old_enough(self):
        created = (datetime.now(timezone.utc) - timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.assertTrue(cleaner.older_than({"created_at": created}, 7))

    def test_too_recent(self):
        created = (datetime.now(timezone.utc) - timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.assertFalse(cleaner.older_than({"created_at": created}, 7))

    def test_unknown_timestamp_is_never_deleted_by_age(self):
        self.assertFalse(cleaner.older_than({}, 7))


if __name__ == "__main__":
    unittest.main()
