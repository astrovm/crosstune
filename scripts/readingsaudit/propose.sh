#!/usr/bin/env bash
# Proposes the refreshed lists and EDRDG notices from a checkout of main.
# The JMdict workflow sets the paths, the branch, the upstream tag and the run URL.
#
# It needs git, with origin able to push, and gh with a token that can write pull requests.
# propose.test.sh runs it on a local repository and a fake gh.
set -euo pipefail
: "${BRANCH:?}" "${TABLES:?}" "${NOTICE_MANIFEST:?}" "${SOURCE_COMMIT:?}" "${TAG:?}" "${RUN:?}"
lists=("$TABLES/jmdict-readings.txt" "$TABLES/old-kana.txt")
snapshot=("${lists[@]}" "$TABLES/notices/edrdg-licence.txt" "$TABLES/notices/jmdict-documentation.txt" "$NOTICE_MANIFEST")

# Compare the committed files before looking at the downloaded files in the working tree.
inputs=("${snapshot[@]}" .github/workflows/jmdict.yml scripts/readingsaudit scripts/notices.py scripts/kotlinc
        app/src/test/java/com/astrovm/crosstune/songcheck/JmdictReadings.kt)
if ! git merge-base --is-ancestor "$SOURCE_COMMIT" HEAD ||
   ! git diff --quiet "$SOURCE_COMMIT" HEAD -- "${inputs[@]}"; then
  echo "::error::JMdict inputs on main changed since the snapshot was made. Run the refresh again."
  exit 1
fi

if git diff --quiet -- "${snapshot[@]}"; then
  echo "::notice::The snapshot is already current for JMdict $TAG"
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
  # before it opened its pull request, so that is still seen to.
  if git merge-base --is-ancestor origin/main "origin/$BRANCH" && git diff --quiet "origin/$BRANCH" -- "${snapshot[@]}"; then
    echo "::notice::$BRANCH already has the snapshot for JMdict $TAG"
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
  git add -- "${snapshot[@]}"
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
  echo "The bundled licence and documentation are copied from EDRDG with this refresh."
  echo
  echo '```'
  git diff --stat "$base" "$head"
  echo '```'
  echo
  # GitHub holds the runs of a pull request opened or pushed with the workflow's token until a person
  # with write access approves them. Started any other way, CI never counts as the pull request's checks.
  echo "To run CI, select **Approve workflows to run** at the bottom of this pull request."
  echo
  echo "## Done when"
  echo
  echo "- [ ] CI passes. A readings test that fails means JMdict now reads a word another way: find out which is right, and fix the reading or the word list, never the test to pass."
  echo "- [ ] The locked readings check passes. The word lists make readings, so it fails until this body says how the locked readings change: run \`readingsaudit.py golden\` on this branch and push the file, or add a line \`golden: none\` here when no reading changes. Then run \`readingsaudit.py lines\`, which reads every audit line whole with main's lists and these, and add the \`golden: lines checked\` line it prints."
  echo "- [ ] The lines taken out of \`jmdict-readings.txt\` make sense: a word goes when JMdict now has two readings for it, or the app reads it right by itself."
  echo "- [ ] Optional: measure on songs with \`readingsaudit.py run\` and \`compare --before\` (see CONTRIBUTING.md)."
} > "$body"
pr=$(gh pr list --head "$BRANCH" --base main --state open --json number --jq '.[0].number // empty')
if [ -n "$pr" ]; then
  if $push; then
    gh pr edit "$pr" --title "$title" --body-file "$body"
  fi
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
