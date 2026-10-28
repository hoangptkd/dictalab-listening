"""Utility script to inspect and clean failed/unimportant workflow runs, artifacts and iOS releases.

Usage:
    python scripts/clean-github-actions.py --list
    python scripts/clean-github-actions.py --delete-failed --dry-run
    python scripts/clean-github-actions.py --delete-failed --min-age-days 7
    python scripts/clean-github-actions.py --workflow ios.yml --workflow training-offline.yml --delete-workflow-runs
    python scripts/clean-github-actions.py --delete-artifacts --min-age-days 3
    python scripts/clean-github-actions.py --delete-releases --dry-run

Token resolution order: --token-file, GH_TOKEN, GITHUB_TOKEN, interactive prompt.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

API_BASE = "https://api.github.com"
FAILED_CONCLUSIONS = ("failure", "cancelled", "timed_out", "startup_failure")
DEFAULT_RELEASE_PREFIX = "ios-personal-"
DELETE_DELAY_SECONDS = 0.3


def get_token(token_file: str | None) -> str:
    if token_file:
        path = Path(token_file).expanduser()
        if not path.is_file():
            print(f"Error: token file not found: {path}", file=sys.stderr)
            sys.exit(1)
        token = path.read_text(encoding="utf-8").strip()
        if token:
            return token
    for name in ("GH_TOKEN", "GITHUB_TOKEN"):
        token = (os.environ.get(name) or "").strip()
        if token:
            return token
    try:
        import getpass
        return getpass.getpass("Enter GitHub Personal Access Token (repo + actions:write): ").strip()
    except Exception:
        return ""


def api_request(path: str, token: str, method: str = "GET") -> tuple[int, dict | list | None]:
    url = f"{API_BASE}{path}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "DictaLab-Actions-Cleaner",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    req = urllib.request.Request(url, headers=headers, method=method)
    for attempt in (1, 2):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = resp.read().decode("utf-8")
                return resp.status, (json.loads(body) if body else None)
        except urllib.error.HTTPError as err:
            if err.code in (403, 429) and attempt == 1:
                time.sleep(5)
                continue
            return err.code, None
        except urllib.error.URLError as err:
            print(f"Network error: {err.reason}", file=sys.stderr)
            return 0, None
    return 0, None


def paginate(path: str, token: str, key: str) -> list[dict]:
    items: list[dict] = []
    page = 1
    while True:
        status, data = api_request(f"{path}?per_page=100&page={page}", token)
        if status != 200 or not isinstance(data, dict):
            break
        batch = data.get(key) or []
        if not batch:
            break
        items.extend(batch)
        total = data.get("total_count")
        if total is not None and len(items) >= total:
            break
        page += 1
    return items


def fetch_all_runs(repo: str, token: str) -> list[dict]:
    return paginate(f"/repos/{repo}/actions/runs", token, "workflow_runs")


def fetch_all_artifacts(repo: str, token: str) -> list[dict]:
    return paginate(f"/repos/{repo}/actions/artifacts", token, "artifacts")


def fetch_releases(repo: str, token: str) -> list[dict]:
    return paginate(f"/repos/{repo}/releases", token, "releases")


def iso_age_days(value: str | None) -> float | None:
    if not value:
        return None
    try:
        created = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if created.tzinfo is None:
        created = created.replace(tzinfo=timezone.utc)
    return (datetime.now(timezone.utc) - created).total_seconds() / 86400


def older_than(item: dict, min_age_days: float) -> bool:
    if min_age_days <= 0:
        return True
    age = iso_age_days(item.get("created_at"))
    return age is not None and age >= min_age_days


def workflow_key(run: dict) -> str:
    return (run.get("path") or "").rsplit("/", 1)[-1]


def matches_workflow(run: dict, selectors: list[str]) -> bool:
    if not selectors:
        return True
    path = workflow_key(run)
    name = run.get("name") or ""
    return any(sel in (path, name) for sel in selectors)


def mb(size_bytes: int) -> float:
    return size_bytes / (1024 * 1024)


def delete_item(path: str, token: str, label: str, dry_run: bool) -> bool:
    if dry_run:
        print(f"[DRY-RUN] Would delete {label}")
        return True
    status, _ = api_request(path, token, method="DELETE")
    if status in (200, 204):
        print(f"Deleted {label}")
        time.sleep(DELETE_DELAY_SECONDS)
        return True
    print(f"Failed to delete {label} (HTTP {status})")
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect and delete failed/obsolete GitHub Actions runs, artifacts and iOS releases.")
    parser.add_argument("--repo", default="hoangptkd/dictalab-listening", help="GitHub repo owner/name")
    parser.add_argument("--token-file", help="File containing a GitHub token (kept out of argv)")
    parser.add_argument("--list", action="store_true", help="List runs, artifacts and matching releases")
    parser.add_argument("--delete-failed", action="store_true", help="Delete failed, cancelled and timed_out workflow runs")
    parser.add_argument("--delete-workflow-runs", action="store_true", help="Delete every run of the workflows selected with --workflow")
    parser.add_argument("--workflow", action="append", default=[], help="Workflow file name (e.g. ios.yml) or workflow name; repeatable")
    parser.add_argument("--delete-artifacts", action="store_true", help="Delete artifacts to free Actions storage quota")
    parser.add_argument("--delete-releases", action="store_true", help="Delete releases whose tag starts with --release-prefix")
    parser.add_argument("--release-prefix", default=DEFAULT_RELEASE_PREFIX, help="Tag prefix for --delete-releases")
    parser.add_argument("--min-age-days", type=float, default=0, help="Only delete items older than N days")
    parser.add_argument("--dry-run", action="store_true", help="Preview deletions without calling DELETE")
    args = parser.parse_args()

    if args.delete_workflow_runs and not args.workflow:
        print("Error: --delete-workflow-runs requires at least one --workflow.", file=sys.stderr)
        sys.exit(2)

    token = get_token(args.token_file)
    if not token:
        print("Error: no GitHub token. Use --token-file, GH_TOKEN or GITHUB_TOKEN.", file=sys.stderr)
        sys.exit(1)

    print(f"Connecting to GitHub API for {args.repo}...")
    status, repo_info = api_request(f"/repos/{args.repo}", token)
    if status != 200 or not isinstance(repo_info, dict):
        print(f"Error: cannot access {args.repo} (HTTP {status}). Token needs repo access (and actions:write / contents:write for deletions).", file=sys.stderr)
        sys.exit(1)
    print(f"Repository access verified: {repo_info.get('full_name')} (private={repo_info.get('private')})")

    runs = fetch_all_runs(args.repo, token)
    artifacts = fetch_all_artifacts(args.repo, token)
    releases = fetch_releases(args.repo, token)

    selected_runs = [r for r in runs if matches_workflow(r, args.workflow)]
    failed_runs = [r for r in selected_runs if r.get("conclusion") in FAILED_CONCLUSIONS and older_than(r, args.min_age_days)]
    deletable_runs = [a for a in artifacts if older_than(a, args.min_age_days)]
    matching_releases = [rel for rel in releases if (rel.get("tag_name") or "").startswith(args.release_prefix)]
    release_bytes = sum(asset.get("size", 0) for rel in matching_releases for asset in rel.get("assets") or [])
    artifact_bytes = sum(a.get("size_in_bytes", 0) for a in deletable_runs)

    print("\n--- Summary ---")
    print(f"Workflow runs total: {len(runs)}")
    print(f"Failed/cancelled runs: {len(failed_runs)} (filter={'yes' if args.workflow else 'no'}, min-age={args.min_age_days:g}d)")
    print(f"Artifacts: {len(artifacts)} total, {len(deletable_runs)} eligible (~{mb(artifact_bytes):.2f} MB)")
    print(f"Releases '{args.release_prefix}*': {len(matching_releases)} (~{mb(release_bytes):.2f} MB of assets)")

    if args.workflow:
        by_workflow: dict[str, int] = {}
        for run in selected_runs:
            by_workflow[workflow_key(run)] = by_workflow.get(workflow_key(run), 0) + 1
        for name, count in sorted(by_workflow.items()):
            print(f"  {name}: {count} run(s)")

    if args.list or not (args.delete_failed or args.delete_workflow_runs or args.delete_artifacts or args.delete_releases):
        print("\n--- Failed / Cancelled Runs ---")
        for run in failed_runs:
            print(f"  {run['id']} | {workflow_key(run)} | {run.get('head_branch')} | {run.get('conclusion')} | {run.get('created_at')}")
        print("\n--- Artifacts ---")
        for art in deletable_runs:
            print(f"  {art['id']} | {art.get('name')} | {mb(art.get('size_in_bytes', 0)):.2f} MB | expired={art.get('expired')} | {art.get('created_at')}")
        print(f"\n--- Releases '{args.release_prefix}*' ---")
        for rel in matching_releases:
            size = sum(asset.get("size", 0) for asset in rel.get("assets") or [])
            print(f"  {rel['id']} | {rel.get('tag_name')} | {mb(size):.2f} MB | {rel.get('created_at')}")
        if not (args.delete_failed or args.delete_workflow_runs or args.delete_artifacts or args.delete_releases):
            print("\nUse --delete-failed / --delete-workflow-runs / --delete-artifacts / --delete-releases (add --dry-run first).")
            return

    if args.delete_artifacts:
        print(f"\nArtifacts to delete: {len(deletable_runs)} (~{mb(artifact_bytes):.2f} MB)")
        for art in sorted(deletable_runs, key=lambda a: a.get("created_at") or ""):
            delete_item(f"/repos/{args.repo}/actions/artifacts/{art['id']}", token, f"artifact {art['id']} ({art.get('name')}, {mb(art.get('size_in_bytes', 0)):.2f} MB)", args.dry_run)

    handled_runs: set[int] = set()

    if args.delete_failed:
        print(f"\nFailed/cancelled runs to delete: {len(failed_runs)}")
        for run in failed_runs:
            if run["id"] in handled_runs:
                continue
            handled_runs.add(run["id"])
            delete_item(f"/repos/{args.repo}/actions/runs/{run['id']}", token, f"run {run['id']} ({workflow_key(run)}, {run.get('conclusion')})", args.dry_run)

    if args.delete_workflow_runs:
        print(f"\nRuns to delete for {', '.join(args.workflow)}: {len(selected_runs)}")
        for run in selected_runs:
            if run["id"] in handled_runs:
                continue
            handled_runs.add(run["id"])
            delete_item(f"/repos/{args.repo}/actions/runs/{run['id']}", token, f"run {run['id']} ({workflow_key(run)}, {run.get('conclusion')})", args.dry_run)

    if args.delete_releases:
        print(f"\nReleases to delete: {len(matching_releases)} (~{mb(release_bytes):.2f} MB)")
        for rel in matching_releases:
            tag = rel.get("tag_name")
            if delete_item(f"/repos/{args.repo}/releases/{rel['id']}", token, f"release {tag}", args.dry_run) and not args.dry_run:
                api_request(f"/repos/{args.repo}/git/refs/tags/{tag}", token, method="DELETE")

    print("\nDone.")


if __name__ == "__main__":
    main()
