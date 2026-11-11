#!/usr/bin/env bash
# Opens the JMdict workflow's one pull request with the word lists it remade, or brings the open one up
# to date, then starts CI on it. Run from a checkout of main whose $TABLES hold the remade lists.
#
#   BRANCH=jmdict/remake TABLES=app/src/main/resources/com/astrovm/crosstune TAG=3.6.2+20261109214828 RUN=URL \
#     scripts/readingsaudit/propose.sh
#
# It needs git, with origin able to push, and gh with a token that can write pull requests and start
# workflows. propose.test.sh runs it on a local repository and a fake gh.
set -euo pipefail
: "${BRANCH:?}" "${TABLES:?}" "${TAG:?}" "${RUN:?}"
lists=("$TABLES/jmdict-readings.txt" "$TABLES/old-kana.txt")

if git diff --quiet -- "${lists[@]}"; then
  echo "::notice::The word lists are already what JMdict $TAG makes"
  exit 0
fi
bot="41898282+github-actions[bot]@users.noreply.github.com"
base=$(git rev-parse HEAD)
lease=""
push=true
head=$BRANCH
# Exit code 2 is no such branch. Anything else is a failure, never taken as no branch.
found=0
git ls-remote -q --exit-code origin "refs/heads/$BRANCH" > /dev/null || found=$?
if [ "$found" = 0 ]; then
  git fetch -q --no-tags origin "+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"
  # The branch is made again from main each time. A commit a person wrote, rebased or amended is
  # never thrown away: each must be the workflow's as author and as committer. The addresses are read
  # whole first: grep -q leaves at the first match, and SIGPIPE on git log would make it no match.
  addresses=$(git log --format='%ae%n%ce' "origin/main..origin/$BRANCH")
  if [ -n "$addresses" ] && grep -vqxF "$bot" <<< "$addresses"; then
    echo "::error::$BRANCH has a commit by a person. Merge or close its pull request, then run this again."
    exit 1
  fi
  lease=$(git rev-parse "origin/$BRANCH")
  # Already on main and made from this JMdict: nothing to push. A run that pushed it may have failed
  # before its pull request or CI, so those are still seen to.
  if git merge-base --is-ancestor origin/main "origin/$BRANCH" && git diff --quiet "origin/$BRANCH" -- "${lists[@]}"; then
    echo "::notice::$BRANCH already has the word lists JMdict $TAG makes"
    push=false
    head=origin/$BRANCH
  fi
elif [ "$found" != 2 ]; then
  echo "::error::Can't read $BRANCH from origin (git ls-remote exit $found)"
  exit 1
fi

# The JMdict date in a list's first line, from stdin. It reads to the end: a reader that leaves early,
# like head -n1, ends git show with SIGPIPE on a list bigger than a pipe holds, and pipefail stops the run.
jmdict_date() { sed -n '1s/^# JMdict \([0-9-]*\),.*/\1/p'; }
before=$(git show "$base:${lists[0]}" | jmdict_date)
after=$(jmdict_date < "${lists[0]}")
title="chore(readings): remake the JMdict word lists from JMdict $after"
if $push; then
  git switch -q -C "$BRANCH"
  git add -- "${lists[@]}"
  git -c user.name="github-actions[bot]" -c user.email="$bot" commit -q -m "$title" \
    -m "From jmdict-simplified $TAG, by .github/workflows/jmdict.yml: $RUN"
  # An empty lease: the branch must not be there yet.
  git push -q --force-with-lease="$BRANCH:$lease" origin "$BRANCH"
fi

body=$(mktemp)
trap 'rm -f "$body"' EXIT
{
  echo "## Symptom"
  echo
  echo "The EDRDG licence asks for JMdict's data to be updated regularly from its newest version. The word lists were from JMdict $before."
  echo
  echo "## Change"
  echo
  echo "\`readingsaudit.py table\` and \`oldkana\` again, from jmdict-simplified \`$TAG\` (JMdict $after), checked against the size and SHA-256 its release gives. [Run]($RUN)."
  echo
  echo '```'
  git diff --stat "$base" "$head"
  echo '```'
  echo
  echo "## Done when"
  echo
  echo "- [ ] CI passes. A readings test that fails means JMdict now reads a word another way: find out which is right, and fix the reading or the word list, never the test to pass."
  echo "- [ ] The lines taken out of \`jmdict-readings.txt\` make sense: a word goes when JMdict now has two readings for it, or the app reads it right by itself."
  echo "- [ ] Optional: measure on songs with \`readingsaudit.py run\` and \`compare --before\` (see CONTRIBUTING.md)."
} > "$body"
pr=$(gh pr list --head "$BRANCH" --base main --state open --json number --jq '.[0].number // empty')
if [ -n "$pr" ]; then
  gh pr edit "$pr" --title "$title" --body-file "$body"
else
  if ! said=$(gh pr create --head "$BRANCH" --base main --title "$title" --body-file "$body" 2>&1); then
    echo "$said" >&2
    if grep -q "not permitted to create or approve pull requests" <<< "$said"; then
      echo "::error::GitHub doesn't let this workflow open pull requests. Turn on Settings → Actions → General → Workflow permissions → Allow GitHub Actions to create and approve pull requests, then run the JMdict workflow again. $BRANCH is pushed."
    fi
    exit 1
  fi
  echo "$said"
fi
# A pull request opened or pushed with the workflow's token starts no workflow.
gh workflow run ci.yml --ref "$BRANCH"
