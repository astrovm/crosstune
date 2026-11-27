#!/usr/bin/env python3
"""Deletes the GitHub Actions caches nothing will restore again, so the repository stays under its 10 GB.

  python3 scripts/cache-prune/prune_caches.py [--dry-run]

Needs `gh` signed in with a token that may write Actions (GH_TOKEN in the workflow).

Two kinds go:
1. CodeQL's default setup saves a new codeql-dependencies cache, about 570 MB, for nearly every push to a pull
   request, and a branch only ever restores its newest. On 2026-11-23 eleven of them filled 6 GB in two hours, and
   GitHub evicted the small caches CI's jobs need (apt-ffmpeg, kotlinc), which then ran out of time downloading.
   The newest of each branch stays.
2. A closed pull request's caches: nothing runs on its branch again.
Everything else is left to GitHub's own eviction.
"""

import argparse
import json
import re
import subprocess
import sys

CODEQL_DEPENDENCIES = "codeql-dependencies-"
PULL_REF = re.compile(r"^refs/pull/(\d+)/")


def to_delete(caches: list[dict], open_pulls: set[int]) -> list[dict]:
    """The caches to delete, from the API's cache entries and the numbers of the open pull requests."""
    gone = []
    newest_codeql: dict[str, dict] = {}
    for cache in caches:
        pull = PULL_REF.match(cache["ref"])
        if pull and int(pull.group(1)) not in open_pulls:
            gone.append(cache)
        elif cache["key"].startswith(CODEQL_DEPENDENCIES):
            kept = newest_codeql.get(cache["ref"])
            if kept is None:
                newest_codeql[cache["ref"]] = cache
            elif cache["created_at"] > kept["created_at"]:
                gone.append(kept)
                newest_codeql[cache["ref"]] = cache
            else:
                gone.append(cache)
    return gone


def gh_json(*args: str):
    return json.loads(
        subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument(
        "--dry-run", action="store_true", help="say what would go, delete nothing"
    )
    args = parser.parse_args(argv)
    pages = gh_json(
        "api",
        "--paginate",
        "--slurp",
        "repos/{owner}/{repo}/actions/caches?per_page=100",
    )
    caches = [cache for page in pages for cache in page["actions_caches"]]
    open_pulls = {
        pull["number"]
        for pull in gh_json(
            "pr", "list", "--state", "open", "--limit", "1000", "--json", "number"
        )
    }
    gone = to_delete(caches, open_pulls)
    for cache in gone:
        print(
            f"{'would delete' if args.dry_run else 'deleting'} {cache['size_in_bytes'] // 2**20} MB {cache['ref']} {cache['key']}"
        )
        if not args.dry_run:
            subprocess.run(
                [
                    "gh",
                    "api",
                    "-X",
                    "DELETE",
                    f"repos/{{owner}}/{{repo}}/actions/caches/{cache['id']}",
                ],
                check=True,
                capture_output=True,
            )
    print(
        f"{len(gone)} of {len(caches)} caches, {sum(c['size_in_bytes'] for c in gone) // 2**20} MB"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
